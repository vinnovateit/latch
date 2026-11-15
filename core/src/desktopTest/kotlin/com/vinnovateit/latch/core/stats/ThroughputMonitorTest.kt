package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.ByteCounts
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ThroughputMonitorTest {

    /**
     * Each interval's byte count is added to the live session totals, so every
     * interval has to reach the collector -- including one that happens to equal
     * the interval before it, as it does under steady traffic.
     */
    @Test
    fun `every interval reaches the collector even when two in a row are equal`() = runBlocking {
        val counter = AtomicLong(0)
        val source = object : ByteCounterSource {
            override fun sample() = counter.addAndGet(1_000).let { ByteCounts(it, 0) }
        }
        val clock = AtomicLong(0)
        val monitor = ThroughputMonitor(source, intervalMs = 10, clock = { clock.addAndGet(10) })

        monitor.start()
        try {
            val intervals = withTimeout(5_000) {
                monitor.dataUsageFlow.filter { it.rxBytes > 0 }.take(5).toList()
            }
            assertEquals(List(5) { 1_000L }, intervals.map { it.rxBytes })
        } finally {
            monitor.stop()
        }
    }
}
