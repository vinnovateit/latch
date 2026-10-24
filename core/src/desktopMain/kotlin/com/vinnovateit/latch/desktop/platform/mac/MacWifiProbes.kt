package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.desktop.platform.BoundedCommandRunner
import com.vinnovateit.latch.desktop.platform.CommandOutcome
import com.vinnovateit.latch.desktop.platform.nonEmptyOutput
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val NETWORKSETUP = "/usr/sbin/networksetup"
private const val IPCONFIG = "/usr/sbin/ipconfig"

/**
 * Puts a hard deadline on another probe. Each read runs on one worker thread
 * and is abandoned at [timeoutMillis]: a native call that never returns then
 * costs that worker, not the engine or the poller, and every read queued
 * behind it reports [MacWifiReading.Unavailable] in turn -- never a guess.
 */
internal class TimeBoundedProbe(
    private val delegate: MacWifiProbe,
    private val timeoutMillis: Long,
    threadName: String,
) : MacWifiProbe {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }

    override fun read(): MacWifiReading {
        val pending = try {
            worker.submit<MacWifiReading> { delegate.read() }
        } catch (e: Exception) {
            return MacWifiReading.Unavailable("Wi-Fi read rejected: ${e.message}")
        }
        return try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.cancel(true)
            MacWifiReading.Unavailable("no answer within $timeoutMillis ms")
        } catch (e: ExecutionException) {
            MacWifiReading.Unavailable("read failed: ${e.cause?.message ?: e.message}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            MacWifiReading.Unavailable("interrupted")
        }
    }
}

/**
 * Reads the Wi-Fi state from CoreWLAN, Apple's supported Wi-Fi API.
 *
 * The interface CoreWLAN returns is by definition the Wi-Fi interface, so
 * there is no port-name matching to get wrong. Always used behind a
 * [TimeBoundedProbe] (see [create]).
 *
 * Constructing one loads CoreWLAN and throws if it cannot, which is the
 * caller's cue to use [NetworksetupWifiProbe] instead.
 */
internal class CoreWlanProbe private constructor(
    private val location: () -> MacLocationAccess,
) : MacWifiProbe {

    companion object {
        fun create(
            location: () -> MacLocationAccess = MacLocationServices::access,
            timeoutMillis: Long = 3_000,
        ): MacWifiProbe = TimeBoundedProbe(CoreWlanProbe(location), timeoutMillis, "latch-corewlan")
    }

    init {
        ObjCRuntime.loadFramework("CoreWLAN")
        checkNotNull(ObjCRuntime.cls("CWWiFiClient")) { "CoreWLAN has no CWWiFiClient" }
    }

    override fun read(): MacWifiReading = ObjCRuntime.withAutoreleasePool {
        val client = ObjCRuntime.send(ObjCRuntime.cls("CWWiFiClient"), "sharedWiFiClient")
            ?: return@withAutoreleasePool MacWifiReading.Unavailable("CWWiFiClient.sharedWiFiClient returned nil")
        val wifi = ObjCRuntime.send(client, "interface")
            ?: return@withAutoreleasePool MacWifiReading.NoInterface
        val name = ObjCRuntime.string(ObjCRuntime.send(wifi, "interfaceName"))
        if (name == null || !MAC_INTERFACE_NAME.matches(name)) {
            return@withAutoreleasePool MacWifiReading.Unavailable("Wi-Fi interface reported no usable name")
        }
        if (!ObjCRuntime.sendBool(wifi, "powerOn")) return@withAutoreleasePool MacWifiReading.PoweredOff(name)
        MacWifiReading.PoweredOn(
            interfaceName = name,
            ssid = ObjCRuntime.string(ObjCRuntime.send(wifi, "ssid")),
            location = location(),
        )
    }
}

/**
 * The fallback when CoreWLAN cannot be loaded: finds the Wi-Fi device and its
 * power state with `networksetup`. It has no way to read the SSID, so a
 * powered-on radio is always reported with the name unknown -- which the
 * engine treats as "not a campus network". Degraded, never permissive.
 */
internal class NetworksetupWifiProbe(
    private val runner: BoundedCommandRunner = BoundedCommandRunner(timeoutMillis = 5_000),
    private val networksetup: String = NETWORKSETUP,
) : MacWifiProbe {

    @Volatile private var device: String? = null

    override fun read(): MacWifiReading {
        val name = device ?: when (val ports = runner.run(listOf(networksetup, "-listallhardwareports"))) {
            is CommandOutcome.Succeeded -> parseWifiDevices(ports.stdout).firstOrNull()
                ?: return MacWifiReading.NoInterface
            else -> return MacWifiReading.Unavailable("networksetup -listallhardwareports: ${ports.describe()}")
        }.also { device = it }

        val power = runner.run(listOf(networksetup, "-getairportpower", name))
        return when (power.nonEmptyOutput?.let(::parseAirportPower)) {
            true -> MacWifiReading.PoweredOn(name, ssid = null, location = MacLocationAccess.Unknown)
            false -> MacWifiReading.PoweredOff(name)
            null -> {
                // The device may have gone away (a USB adapter); look again next time.
                device = null
                MacWifiReading.Unavailable("networksetup -getairportpower $name: ${power.describe()}")
            }
        }
    }
}

/** IPv4 from the JVM's own interface table; the gateway from `ipconfig`, bounded. */
internal class SystemMacLinkInspector(
    private val runner: BoundedCommandRunner = BoundedCommandRunner(timeoutMillis = 3_000),
) : MacLinkInspector {

    override fun ipv4Address(interfaceName: String): String? = try {
        NetworkInterface.getByName(interfaceName)?.takeIf { it.isUp }?.inetAddresses?.asSequence()
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    override fun gateway(interfaceName: String): String? =
        runner.run(listOf(IPCONFIG, "getoption", interfaceName, "router")).nonEmptyOutput?.let(::parseRouter)
}

/** `networksetup -setairportpower <device> on`, bounded. */
internal class NetworksetupWifiPower(
    private val runner: BoundedCommandRunner = BoundedCommandRunner(timeoutMillis = 5_000),
) : MacWifiPower {
    override fun turnOn(interfaceName: String): Boolean =
        runner.run(listOf(NETWORKSETUP, "-setairportpower", interfaceName, "on")) is CommandOutcome.Succeeded
}

internal fun CommandOutcome.describe(): String = when (this) {
    is CommandOutcome.Unavailable -> "could not run ($reason)"
    CommandOutcome.TimedOut -> "timed out"
    is CommandOutcome.Failed -> "exit $exitCode${stderr.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}"
    is CommandOutcome.Succeeded -> if (stdout.isEmpty()) "no output" else "unexpected output"
}
