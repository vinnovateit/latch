package com.vinnovateit.latch.desktop.platform.mac

/**
 * Whether macOS lets this process see the Wi-Fi network's name.
 *
 * Since macOS 14 the SSID is location data: without Location Services
 * permission CoreWLAN reports it as nil, exactly as if there were no network.
 * Anything but [Granted] therefore means "the name is hidden from Latch", and
 * says nothing at all about which network this is.
 */
internal enum class MacLocationAccess { Granted, NotDetermined, Denied, Restricted, Unknown }

/** One read of the Wi-Fi hardware. Every failure is a case of its own, never a guess. */
internal sealed interface MacWifiReading {
    /** No Wi-Fi hardware (or none macOS reports), e.g. a Mac mini on Ethernet only. */
    data object NoInterface : MacWifiReading

    data class PoweredOff(val interfaceName: String) : MacWifiReading

    /**
     * The radio is on. [ssid] is null both when it is not associated and when
     * the name is hidden -- [location] is what tells those apart.
     */
    data class PoweredOn(
        val interfaceName: String,
        val ssid: String?,
        val location: MacLocationAccess,
    ) : MacWifiReading

    /** The read itself failed or did not finish in time; nothing is known. */
    data class Unavailable(val reason: String) : MacWifiReading
}

/**
 * Reads the Wi-Fi state once. Implementations must return within a bounded
 * time and report failure as [MacWifiReading.Unavailable], never by throwing.
 */
internal fun interface MacWifiProbe {
    fun read(): MacWifiReading
}

/** The per-interface facts that are not location-protected. Both answer null when unknown. */
internal interface MacLinkInspector {
    /** The interface's routable IPv4 address, or null when it has none. */
    fun ipv4Address(interfaceName: String): String?

    fun gateway(interfaceName: String): String?
}

/** Switches the radio on; true only if the request was accepted. */
internal fun interface MacWifiPower {
    fun turnOn(interfaceName: String): Boolean
}

/**
 * The hardware port names macOS gives the Wi-Fi interface: "Wi-Fi" since OS X
 * 10.7, "AirPort" before that and on some upgraded systems. Matched whole,
 * never as a substring, so "Thunderbolt Ethernet" or "USB 10/100/1000 LAN"
 * can never qualify.
 */
private val WIFI_PORT_NAMES = setOf("wi-fi", "airport")

/** A BSD interface name as macOS reports it (en0, en1, ...). */
internal val MAC_INTERFACE_NAME = Regex("[a-z]+[0-9]+")

/**
 * The Wi-Fi devices in `networksetup -listallhardwareports` output.
 *
 * The output is a series of blank-line-separated blocks, each a
 * "Hardware Port:" line followed by its "Device:". Each block is parsed on
 * its own and a device is taken only from a block whose port is a Wi-Fi port,
 * so no amount of reordering, extra lines or missing fields can make an
 * Ethernet device come out of here.
 */
internal fun parseWifiDevices(listAllHardwarePorts: String): List<String> =
    listAllHardwarePorts
        .replace("\r\n", "\n")
        .split(Regex("\n\\s*\n"))
        .mapNotNull { block ->
            val fields = block.lines().mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }.toMap()
            val port = fields["Hardware Port"]?.lowercase() ?: return@mapNotNull null
            val device = fields["Device"] ?: return@mapNotNull null
            device.takeIf { port in WIFI_PORT_NAMES && MAC_INTERFACE_NAME.matches(it) }
        }

/**
 * `networksetup -getairportpower <device>` prints "Wi-Fi Power (en0): On".
 * Anything else -- an error message, an unknown device -- is null, not "off".
 */
internal fun parseAirportPower(output: String): Boolean? {
    val value = output.trim().substringAfterLast(':', missingDelimiterValue = "").trim()
    return when (value.lowercase()) {
        "on" -> true
        "off" -> false
        else -> null
    }
}

private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")

/** `ipconfig getoption <device> router` prints the bare address; anything else is null. */
internal fun parseRouter(output: String): String? = output.trim().takeIf { IPV4.matches(it) }
