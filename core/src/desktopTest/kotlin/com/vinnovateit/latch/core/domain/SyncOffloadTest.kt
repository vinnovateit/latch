package com.vinnovateit.latch.core.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vinnovateit.latch.core.data.LatchDatabase
import com.vinnovateit.latch.core.mp.JHttpConnection
import com.vinnovateit.latch.core.mp.JUrl
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.portal.PortalHistoryClient
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/** Guards the freeze: a blocking sync must never occupy its caller's thread. */
class SyncOffloadTest {
    private object Handle : NetworkHandle {
        override val id: String = "wlan0"
    }

    private class Counters : ByteCounterSource {
        override fun sample(): ByteCounts = ByteCounts(0L, 0L)
    }

    /** Signals when the first read starts, then blocks until released. */
    private class BlockingTransport(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : HttpTransport {
        override fun open(url: JUrl, handle: NetworkHandle?): JHttpConnection =
            JHttpConnection(object : HttpURLConnection(URL(url.toString())) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false
                override fun getInputStream(): InputStream {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return ByteArrayInputStream("OK".toByteArray())
                }
                override fun getOutputStream() = java.io.ByteArrayOutputStream()
                override fun getResponseCode(): Int = 200
            })
    }

    @Test
    fun `syncPortalHistory leaves its caller dispatcher responsive`(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<LatchDatabase>()
            .setDriver(BundledSQLiteDriver()).build()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = ThroughputMonitor(Counters()),
            portalClient = PortalHistoryClient(BlockingTransport(entered, release)),
            activeHandle = { Handle },
        )
        // Stands in for Dispatchers.Main: one thread, like the SDL UI loop.
        val caller = Dispatchers.Default.limitedParallelism(1, "fake-ui")
        try {
            val sync = async(caller) { repo.syncPortalHistory("24BDS0155", "password") }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "fetch never started")

            val pong = CountDownLatch(1)
            caller.dispatch(EmptyCoroutineContext) { pong.countDown() }
            assertTrue(pong.await(2, TimeUnit.SECONDS), "blocking sync occupied the caller thread")

            release.countDown()
            sync.await()
        } finally {
            release.countDown()
            repo.close()
            db.close()
        }
    }
}
