package com.vinnovateit.latch.platform

import android.net.TrafficStats
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.ByteCounterSource

/**
 * Cumulative counters only -- the delta/smoothing math TrafficStatsLogger used
 * to do by hand is superseded by :core's shared ThroughputMonitor, which
 * already polls a ByteCounterSource and computes smoothed rates generically.
 *
 * total - mobile isolates Wi-Fi(+other) traffic, same approximation
 * TrafficStatsLogger already used.
 */
class AndroidByteCounterSource : ByteCounterSource {
    override fun sample(): ByteCounts? {
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        if (totalRx == TrafficStats.UNSUPPORTED.toLong() || totalTx == TrafficStats.UNSUPPORTED.toLong()) {
            return null
        }
        val mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0)
        val mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0)
        return ByteCounts(rxBytes = totalRx - mobileRx, txBytes = totalTx - mobileTx)
    }
}
