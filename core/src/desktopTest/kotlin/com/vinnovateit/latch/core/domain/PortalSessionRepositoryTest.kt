package com.vinnovateit.latch.core.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vinnovateit.latch.core.data.LatchDatabase
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.portal.PortalHistoryClient
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PortalSessionRepositoryTest {
    private lateinit var db: LatchDatabase

    private class StubCounters : ByteCounterSource {
        override fun sample(): ByteCounts = ByteCounts(0L, 0L)
    }

    private object TestWifiHandle : com.vinnovateit.latch.core.platform.NetworkHandle {
        override val id: String = "wlan0"
    }

    private class FakePortalTransport : HttpTransport {
        val handles = mutableListOf<com.vinnovateit.latch.core.platform.NetworkHandle?>()

        override fun open(url: URL, handle: com.vinnovateit.latch.core.platform.NetworkHandle?): HttpURLConnection {
            handles += handle
            return object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getInputStream(): InputStream {
                    val html = when {
                        "Main.jsp" in url.toString() -> """<form name="chooseAuthForm" action="/registration/chooseAuth.do"></form>"""
                        "CustomerSessionHistory.jsp" in url.toString() -> """
                            <table>
                                <tr bgcolor="#DDDDDD">
                                    <td>VIT-Vellore</td>
                                    <td>9e:85:16:b5:22:eb</td>
                                    <td>09/07/26 11:39:30 PM</td>
                                    <td>09/07/26 11:53:16 PM</td>
                                    <td>13 min 46 sec</td>
                                    <td>15.15 KB</td>
                                    <td>116.02 KB</td>
                                    <td>131.17 KB</td>
                                </tr>
                            </table>
                        """.trimIndent()
                        else -> "OK"
                    }
                    return ByteArrayInputStream(html.toByteArray())
                }
                override fun getOutputStream() = java.io.ByteArrayOutputStream()
                override fun getResponseCode() = 200
            }
        }
    }

    private class SlowPortalTransport : HttpTransport {
        val logins = AtomicInteger(0)
        val threads: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun open(url: URL, handle: com.vinnovateit.latch.core.platform.NetworkHandle?): HttpURLConnection {
            val urlStr = url.toString()
            threads += Thread.currentThread().name
            if ("chooseAuth" in urlStr) logins.incrementAndGet()
            return object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getInputStream(): InputStream {
                    Thread.sleep(150)
                    val html = if ("Main.jsp" in urlStr) {
                        """<form name="chooseAuthForm" action="/registration/chooseAuth.do"></form>"""
                    } else {
                        "OK"
                    }
                    return ByteArrayInputStream(html.toByteArray())
                }
                override fun getOutputStream() = java.io.ByteArrayOutputStream()
                override fun getResponseCode() = 200
            }
        }
    }

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<LatchDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun testSyncPortalHistoryUpdatesStateFlow() = runBlocking {
        val throughput = ThroughputMonitor(StubCounters())
        val portalClient = PortalHistoryClient(FakePortalTransport())
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = throughput,
            portalClient = portalClient,
            activeHandle = { TestWifiHandle },
        )
        repo.initialize()

        val syncRes = repo.syncPortalHistory("24BDS0155", "zero")
        assertTrue(syncRes.isSuccess)

        val history = repo.portalHistory.first { it.isNotEmpty() }
        assertEquals(1, history.size)
        assertEquals("VIT-Vellore", history[0].location)
        repo.close()
    }

    @Test
    fun testSyncPortalHistoryPreservesValidatedPastDates() = runBlocking {
        val throughput = ThroughputMonitor(StubCounters())
        var returnHtml = """
            <table>
                <tr bgcolor="#DDDDDD">
                    <td>VIT-Initial</td>
                    <td>9e:85:16:b5:22:eb</td>
                    <td>01/01/24 10:00:00 AM</td>
                    <td>01/01/24 11:00:00 AM</td>
                    <td>1 hr</td>
                    <td>10.00 MB</td>
                    <td>50.00 MB</td>
                    <td>60.00 MB</td>
                </tr>
            </table>
        """.trimIndent()

        val dynamicTransport = object : HttpTransport {
            override fun open(url: URL, handle: com.vinnovateit.latch.core.platform.NetworkHandle?): HttpURLConnection {
                return object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy(): Boolean = false
                    override fun getInputStream(): InputStream {
                        val html = when {
                            "Main.jsp" in url.toString() -> """<form name="chooseAuthForm" action="/registration/chooseAuth.do"></form>"""
                            "CustomerSessionHistory.jsp" in url.toString() -> returnHtml
                            else -> "OK"
                        }
                        return ByteArrayInputStream(html.toByteArray())
                    }
                    override fun getOutputStream() = java.io.ByteArrayOutputStream()
                    override fun getResponseCode() = 200
                }
            }
        }

        val portalClient = PortalHistoryClient(dynamicTransport)
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = throughput,
            portalClient = portalClient,
            activeHandle = { TestWifiHandle },
        )
        repo.initialize()

        val sync1 = repo.syncPortalHistory("24BDS0155", "zero", force = true)
        assertTrue(sync1.isSuccess)
        val history1 = repo.portalHistory.first { it.isNotEmpty() }
        assertEquals(1, history1.size)
        assertEquals("VIT-Initial", history1[0].location)

        // Portal returns different data for that past date
        returnHtml = """
            <table>
                <tr bgcolor="#DDDDDD">
                    <td>VIT-Corrupted</td>
                    <td>9e:85:16:b5:22:eb</td>
                    <td>01/01/24 10:00:00 AM</td>
                    <td>01/01/24 11:00:00 AM</td>
                    <td>1 hr</td>
                    <td>99.00 MB</td>
                    <td>99.00 MB</td>
                    <td>198.00 MB</td>
                </tr>
            </table>
        """.trimIndent()

        val sync2 = repo.syncPortalHistory("24BDS0155", "zero", force = true)
        assertTrue(sync2.isSuccess)
        val history2 = repo.portalHistory.first()
        assertEquals(1, history2.size)
        // Must preserve the validated past date
        assertEquals("VIT-Initial", history2[0].location)
        repo.close()
    }

    @Test
    fun `two concurrent syncs perform only one portal login`() = runBlocking {
        val transport = SlowPortalTransport()
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = PortalHistoryClient(transport),
            // Slow enough that both callers are inside the function together,
            // which is what happens on a real cold start.
            activeHandle = { Thread.sleep(200); TestWifiHandle },
        )
        repo.initialize()

        // The app graph and the stats screen both fire a sync on startup.
        listOf(
            async(Dispatchers.IO) { repo.syncPortalHistory("24BDS0155", "zero", force = true) },
            async(Dispatchers.IO) { repo.syncPortalHistory("24BDS0155", "zero", force = true) },
        ).awaitAll()

        assertEquals(
            1,
            transport.logins.get(),
            "the portal answers a second simultaneous login with a timeout",
        )
        repo.close()
    }

    @Test
    fun `a sync leaves the thread that started it free`() {
        val transport = SlowPortalTransport()
        val lookupThreads: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = PortalHistoryClient(transport),
            activeHandle = {
                lookupThreads += Thread.currentThread().name
                Thread.sleep(150)
                TestWifiHandle
            },
        )
        // Stands in for the UI thread the Stats screen starts its sync on.
        val uiThread = Executors.newSingleThreadExecutor { Thread(it, "ui") }
        val ui = uiThread.asCoroutineDispatcher()
        try {
            runBlocking {
                repo.initialize()
                var frames = 0
                val frameLoop = launch(ui) {
                    while (true) {
                        frames++
                        delay(10)
                    }
                }
                withContext(ui) { repo.syncPortalHistory("24BDS0155", "zero", force = true) }
                frameLoop.cancelAndJoin()

                assertTrue(transport.logins.get() > 0, "the sync reached the portal")
                assertFalse("ui" in lookupThreads, "the network lookup ran on the calling thread")
                assertFalse("ui" in transport.threads, "the portal requests ran on the calling thread")
                // The lookup and requests take over half a second; a blocked thread
                // would have run the loop once or twice.
                assertTrue(frames > 10, "the calling thread only ran $frames times during the sync")
            }
        } finally {
            repo.close()
            uiThread.shutdown()
        }
    }

    @Test
    fun `session summaries are derived from portal history`() = runBlocking {
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = PortalHistoryClient(FakePortalTransport()),
            activeHandle = { TestWifiHandle },
        )
        repo.initialize()

        assertTrue(repo.syncPortalHistory("24BDS0155", "zero", force = true).isSuccess)

        // Local session rows are no longer written, so an empty list here means
        // every consumer of these flows is showing nothing.
        val summaries = repo.sessionSummaries.first { it.isNotEmpty() }
        assertEquals(1, summaries.size)
        assertEquals(118_804L, summaries[0].totalData.rxBytes)
        assertEquals(15_513L, summaries[0].totalData.txBytes)
        assertEquals(summaries[0], repo.lastSession.first { it != null })
        repo.close()
    }

    @Test
    fun `portal sync binds every request to the active Wi-Fi handle`() = runBlocking {
        val transport = FakePortalTransport()
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = PortalHistoryClient(transport),
            activeHandle = { TestWifiHandle },
        )
        repo.initialize()

        assertTrue(repo.syncPortalHistory("24BDS0155", "zero", force = true).isSuccess)

        assertTrue(transport.handles.isNotEmpty(), "the portal client should have opened connections")
        transport.handles.forEach { handle ->
            assertNotNull(handle, "credentials must never be sent on the default network")
            assertEquals(TestWifiHandle.id, handle.id)
        }
        repo.close()
    }

    @Test
    fun `portal sync is refused when no Wi-Fi network is active`() = runBlocking {
        val transport = FakePortalTransport()
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = PortalHistoryClient(transport),
            activeHandle = { null },
        )
        repo.initialize()

        val result = repo.syncPortalHistory("24BDS0155", "zero", force = true)

        assertFalse(result.isSuccess, "sync must fail rather than leave the Wi-Fi network")
        assertTrue(transport.handles.isEmpty(), "no request should have been made at all")
        repo.close()
    }

    @Test
    fun testIsHistoryLoadedFlag() = runBlocking {
        val repo = SessionRepository(
            statsDao = db.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = null
        )
        repo.initialize()
        val loaded = repo.isHistoryLoaded.first { it }
        assertTrue(loaded)
        repo.close()
    }
}
