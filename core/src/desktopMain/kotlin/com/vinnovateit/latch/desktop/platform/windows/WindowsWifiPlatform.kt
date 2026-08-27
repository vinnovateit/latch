package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

internal data class SimpleWindowsNetworkHandle(override val id: String) : NetworkHandle

/**
 * Windows Wi-Fi state via PowerShell.
 */
class WindowsWifiPlatform(private val logger: Logger) : WifiPlatform {

    private companion object {
        const val TAG = "WindowsWifiPlatform"
        const val POLL_INTERVAL_MS = 5_000L
        const val CACHE_TTL_MS = 3_000L
        const val PS_TIMEOUT_SEC = 10L
        const val ENABLE_SETTLE_ATTEMPTS = 6
        const val ENABLE_SETTLE_INTERVAL_MS = 1_000L

        val PS_AWAIT = """
            function Latch-Await(${'$'}op, ${'$'}type) {
              ${'$'}m = [System.WindowsRuntimeSystemExtensions].GetMethods() |
                Where-Object {
                  ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and
                  ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
                } | Select-Object -First 1
              ${'$'}t = ${'$'}m.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op))
              [void]${'$'}t.Wait(5000)
              ${'$'}t.Result
            }
            function Get-LatchWifiRadio {
              [void][Windows.Devices.Radio.Radio, Windows.System.Devices, ContentType = WindowsRuntime]
              ${'$'}list = [System.Collections.Generic.IReadOnlyList[Windows.Devices.Radio.Radio]]
              ${'$'}radios = Latch-Await ([Windows.Devices.Radio.Radio]::GetRadiosAsync()) ${'$'}list
              ${'$'}radios | Where-Object { ${'$'}_.Kind -eq 'WiFi' } | Select-Object -First 1
            }
        """.trimIndent()
    }

    private data class WifiSnapshot(
        val adapterName: String?,
        val adapterUp: Boolean,
        val radioOn: Boolean?,
        val ssid: String?,
        val gateway: String?,
    )

    private var cached: WifiSnapshot? = null
    private var cachedAt: Long = 0

    private fun invalidate() {
        cached = null
        cachedAt = 0
    }

    /**
     * Turns the raw name the script found into an SSID, or null for "we do not
     * know".
     *
     * Null is a materially different answer from a name that simply is not a
     * campus SSID, and the engine treats it as such: an unknown SSID must not be
     * read as "this is not a VIT network", or a machine whose profile name never
     * resolves can never log in.
     *
     * A `wlan`-sourced value is the real SSID and is taken as-is. A `profile`
     * one is a network-profile name, so the known placeholders are rejected.
     * The list is English-only by necessity -- these strings are localized, and
     * there is no non-localized form to match -- but on a machine where the
     * netsh label is localized the WLAN path usually succeeded anyway, and
     * anything slipping through lands on the DNS check rather than on a wrong
     * "not a campus network" conclusion.
     */
    private fun resolveSsid(raw: String?, source: String?): String? {
        val name = raw?.takeIf { it.isNotEmpty() } ?: return null
        if (source == "wlan") return name
        val placeholder = name.equals("Identifying...", ignoreCase = true) ||
            name.equals("Unidentified network", ignoreCase = true) ||
            name.equals("Network", ignoreCase = true)
        return if (placeholder) {
            logger.d(TAG, "Profile name '$name' is a placeholder, not an SSID; treating as unknown.")
            null
        } else {
            name
        }
    }

    private fun snapshot(): WifiSnapshot {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_TTL_MS) return it }

        val script = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            $PS_AWAIT
            ${'$'}a = Get-NetAdapter -Physical | Where-Object { ${'$'}_.PhysicalMediaType -match '802.11' } | Select-Object -First 1
            ${'$'}name = if (${'$'}a) { ${'$'}a.Name } else { '' }
            ${'$'}up = if (${'$'}a -and ${'$'}a.Status -eq 'Up') { '1' } else { '0' }
            ${'$'}radio = ''
            try {
              ${'$'}w = Get-LatchWifiRadio
              if (${'$'}w) { ${'$'}radio = if (${'$'}w.State -eq 'On') { '1' } else { '0' } }
            } catch { ${'$'}radio = '' }
            ${'$'}ssid = ''
            ${'$'}src = ''
            # The WLAN interface first. Get-NetConnectionProfile returns a
            # *network profile* name, which is only incidentally the SSID: while
            # Network Location Awareness has not classified the network it reads
            # "Identifying..." instead, and on a captive portal -- the exact case
            # this app exists for -- it can sit there indefinitely, because NLA
            # cannot reach the internet to classify anything. netsh reports the
            # real SSID throughout.
            #
            # Anchoring the label at line start is what keeps this off the BSSID
            # line. The label itself is localized on some Windows builds, in
            # which case this finds nothing and the profile name below is used.
            foreach (${'$'}line in (netsh wlan show interfaces)) {
              if (${'$'}line -match '^\s*SSID\s*:\s*(.+)${'$'}') { ${'$'}ssid = ${'$'}matches[1].Trim(); ${'$'}src = 'wlan'; break }
            }
            if (-not ${'$'}ssid -and ${'$'}name) {
              ${'$'}p = Get-NetConnectionProfile -InterfaceAlias ${'$'}name
              if (${'$'}p) { ${'$'}ssid = ${'$'}p.Name; ${'$'}src = 'profile' }
            }
            ${'$'}gw = ''
            if (${'$'}name) {
              ${'$'}r = Get-NetRoute -InterfaceAlias ${'$'}name -DestinationPrefix '0.0.0.0/0' | Select-Object -First 1
              if (${'$'}r) { ${'$'}gw = ${'$'}r.NextHop }
            }
            Write-Output ("RESULT|" + ${'$'}name + "|" + ${'$'}up + "|" + ${'$'}radio + "|" + ${'$'}ssid + "|" + ${'$'}gw + "|" + ${'$'}src)
        """.trimIndent()

        val result = runPowerShell(script)
        val snap = if (result == null) {
            WifiSnapshot(null, false, null, null, null)
        } else {
            val parts = result.removePrefix("RESULT|").split('|')
            WifiSnapshot(
                adapterName = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
                adapterUp = parts.getOrNull(1)?.trim() == "1",
                radioOn = when (parts.getOrNull(2)?.trim()) {
                    "1" -> true
                    "0" -> false
                    else -> null
                },
                ssid = resolveSsid(
                    raw = parts.getOrNull(3)?.trim(),
                    source = parts.getOrNull(5)?.trim(),
                ),
                gateway = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        cached = snap
        cachedAt = now
        return snap
    }

    private fun runPowerShell(script: String): String? {
        return try {
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(script.toByteArray(Charsets.UTF_16LE))

            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
            ).redirectErrorStream(true).start()

            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(PS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.w(TAG, "PowerShell query timed out")
                return null
            }
            val line = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("RESULT|") }
            if (line == null) {
                logger.w(TAG, "PowerShell returned no RESULT line. Raw output: ${output.take(400)}")
            }
            line
        } catch (e: Throwable) {
            logger.e(TAG, "PowerShell query failed", e)
            null
        }
    }

    override fun isWifiEnabled(): Boolean {
        val snap = snapshot()
        return snap.adapterName != null && snap.radioOn != false
    }

    override fun enableWifi(): Boolean {
        if (isWifiEnabled()) return true

        val script = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            $PS_AWAIT
            ${'$'}state = 'unknown'
            try {
              [void][Windows.Devices.Radio.RadioAccessStatus, Windows.System.Devices, ContentType = WindowsRuntime]
              ${'$'}status = [Windows.Devices.Radio.RadioAccessStatus]
              [void](Latch-Await ([Windows.Devices.Radio.Radio]::RequestAccessAsync()) ${'$'}status)
              ${'$'}w = Get-LatchWifiRadio
              if (${'$'}w) {
                if (${'$'}w.State -eq 'On') { ${'$'}state = 'already' }
                else { [void](Latch-Await (${'$'}w.SetStateAsync('On')) ${'$'}status); ${'$'}state = 'set' }
              }
            } catch { ${'$'}state = 'error' }
            Get-NetAdapter -Physical |
              Where-Object { ${'$'}_.PhysicalMediaType -match '802.11' -and ${'$'}_.Status -eq 'Disabled' } |
              Enable-NetAdapter -Confirm:${'$'}false
            Write-Output ("RESULT|" + ${'$'}state)
        """.trimIndent()

        val outcome = runPowerShell(script)?.removePrefix("RESULT|")?.trim()
        logger.d(TAG, "enableWifi: radio result '$outcome'")
        invalidate()

        repeat(ENABLE_SETTLE_ATTEMPTS) {
            if (isWifiEnabled() && isConnectedToWifi()) return true
            Thread.sleep(ENABLE_SETTLE_INTERVAL_MS)
            invalidate()
        }
        return isWifiEnabled()
    }

    override fun isConnectedToWifi(): Boolean {
        val snap = snapshot()
        return snap.adapterUp && snap.ssid != null
    }

    override fun currentSsid(): String? = snapshot().ssid

    override fun gatewayIp(): String? = snapshot().gateway

    override fun activeHandle(): NetworkHandle? =
        snapshot().takeIf { it.adapterUp }?.adapterName?.let { SimpleWindowsNetworkHandle(it) }

    override fun wifiInterfaceName(): String? = snapshot().adapterName

    fun wifiLocalAddress(): java.net.InetAddress? {
        val name = wifiInterfaceName() ?: return null
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.displayName == name || it.name == name }
                ?.inetAddresses?.toList()
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to resolve Wi-Fi local address", e)
            null
        }
    }

    override val events: Flow<WifiEvent> = flow {
        val seed = snapshot()
        var lastKey = if (seed.adapterUp && seed.ssid != null) {
            "${seed.adapterName}::${seed.ssid}"
        } else {
            null
        }

        while (true) {
            val snap = snapshot()
            val key = if (snap.adapterUp && snap.ssid != null) {
                "${snap.adapterName}::${snap.ssid}"
            } else {
                null
            }

            if (key != lastKey) {
                if (lastKey != null) {
                    emit(WifiEvent.Lost(SimpleWindowsNetworkHandle(lastKey.substringBefore("::"))))
                }
                if (key != null) {
                    emit(WifiEvent.Available(SimpleWindowsNetworkHandle(snap.adapterName!!)))
                }
                lastKey = key
            }
            delay(POLL_INTERVAL_MS)
        }
    }
}
