package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.WifiPlatform
import oshi.SystemInfo
import oshi.hardware.NetworkIF

/**
 * Per-interface byte counters via OSHI.
 *
 * This is genuinely more accurate than the Android original, which approximates
 * Wi-Fi throughput as (total bytes - mobile bytes) across the whole device. Here
 * we read the Wi-Fi adapter directly.
 */
class OshiByteCounters(
    private val wifi: WifiPlatform,
    private val logger: Logger,
) : ByteCounterSource {

    private companion object {
        const val TAG = "OshiByteCounters"
    }

    private val systemInfo by lazy { SystemInfo() }
    private var cachedIf: NetworkIF? = null

    override fun sample(): ByteCounts? {
        val name = wifi.wifiInterfaceName() ?: return null

        // Windows adapter naming is inconsistent between name and ifAlias, so
        // match on either.
        val nif = cachedIf?.takeIf { it.name == name || it.ifAlias == name }
            ?: systemInfo.hardware.networkIFs
                .firstOrNull { it.name == name || it.ifAlias == name }
                ?.also { cachedIf = it }
            ?: run {
                logger.w(TAG, "No OSHI interface matching '$name'")
                return null
            }

        // Mandatory on every poll -- without it the counters are frozen at the
        // values they had when the NetworkIF was constructed.
        if (!nif.updateAttributes()) {
            logger.w(TAG, "updateAttributes() failed for '$name'")
            return null
        }

        return ByteCounts(rxBytes = nif.bytesRecv, txBytes = nif.bytesSent)
    }
}
