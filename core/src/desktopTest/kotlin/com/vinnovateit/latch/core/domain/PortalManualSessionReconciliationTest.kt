package com.vinnovateit.latch.core.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vinnovateit.latch.core.data.LatchDatabase
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.portal.PortalHistoryClient
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalManualSessionReconciliationTest {

    private lateinit var database: LatchDatabase
    private lateinit var repository: SessionRepository

    private class StubCounters : ByteCounterSource {
        override fun sample(): ByteCounts = ByteCounts(0L, 0L)
    }

    private object ReconciliationWifiHandle : com.vinnovateit.latch.core.platform.NetworkHandle {
        override val id: String = "wlan0"
    }

    private class FakePortalTransport(var historyHtml: String = "") : HttpTransport {
        override fun open(url: URL, handle: com.vinnovateit.latch.core.platform.NetworkHandle?): HttpURLConnection {
            return object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getInputStream(): InputStream {
                    val html = when {
                        "Main.jsp" in url.toString() -> """<form name="chooseAuthForm" action="/registration/chooseAuth.do"></form>"""
                        "CustomerSessionHistory.jsp" in url.toString() -> historyHtml
                        else -> "OK"
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
        database = Room.inMemoryDatabaseBuilder<LatchDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        if (::repository.isInitialized) {
            runBlocking { repository.closeAndAwait() }
        }
        database.close()
    }

    @Test
    fun `manual session is immediately stored and survives sync when server has not registered it yet`() = runBlocking {
        val transport = FakePortalTransport(historyHtml = "<table></table>")
        val portalClient = PortalHistoryClient(transport)
        repository = SessionRepository(
            statsDao = database.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = portalClient,
            activeHandle = { ReconciliationWifiHandle },
        )
        repository.initialize()

        val logoutTime = System.currentTimeMillis()
        val durationMs = 592_000L // 9m 52s
        val manualRecord = PortalSessionRecord(
            location = "Portal Logout",
            macAddress = "",
            loginTime = logoutTime - durationMs,
            logoutTime = logoutTime,
            durationFormatted = "9 min 52 sec",
            durationMillis = durationMs,
            uploadBytes = 39745L,
            downloadBytes = 2607L,
            totalBytes = 42352L,
            isManual = true
        )

        // 1. Record manual session
        repository.recordManualSession(manualRecord)

        // Verify it is immediately visible in state flow
        val history1 = repository.portalHistory.value
        assertEquals(1, history1.size)
        assertTrue(history1[0].isManual)
        assertEquals(42352L, history1[0].totalBytes)

        // Verify it was persisted to Room
        val entities1 = database.statsDao().getAllPortalSessions().first()
        assertEquals(1, entities1.size)
        assertTrue(entities1[0].isManual)

        // 2. Perform portal sync where server returns empty history (delayed sync)
        repository.syncPortalHistory("user", "pass", force = true)

        // Verify manual record was NOT wiped out and remains pending
        val history2 = repository.portalHistory.value
        assertEquals(1, history2.size)
        assertTrue(history2[0].isManual)
    }

    @Test
    fun `portal sync reconciles and replaces matching manual record with authoritative server record`() = runBlocking {
        val logoutTime = System.currentTimeMillis()
        val durationMs = 592_000L // 9m 52s
        val manualRecord = PortalSessionRecord(
            location = "Portal Logout",
            macAddress = "",
            loginTime = logoutTime - durationMs,
            logoutTime = logoutTime,
            durationFormatted = "9 min 52 sec",
            durationMillis = durationMs,
            uploadBytes = 39745L,
            downloadBytes = 2607L,
            totalBytes = 42352L,
            isManual = true
        )

        // Server record HTML matching the session
        val serverHtml = """
            <table>
                <tr bgcolor="#DDDDDD">
                    <td>VIT-Vellore</td>
                    <td>9e:85:16:b5:22:eb</td>
                    <td>${com.vinnovateit.latch.core.stats.formatDate(manualRecord.loginTime, "MM/dd/yy hh:mm:ss a")}</td>
                    <td>${com.vinnovateit.latch.core.stats.formatDate(manualRecord.logoutTime, "MM/dd/yy hh:mm:ss a")}</td>
                    <td>9 min 52 sec</td>
                    <td>38.81 KB</td>
                    <td>2.55 KB</td>
                    <td>41.36 KB</td>
                </tr>
            </table>
        """.trimIndent()

        val transport = FakePortalTransport(historyHtml = serverHtml)
        val portalClient = PortalHistoryClient(transport)
        repository = SessionRepository(
            statsDao = database.statsDao(),
            throughput = ThroughputMonitor(StubCounters()),
            portalClient = portalClient,
            activeHandle = { ReconciliationWifiHandle },
        )
        repository.initialize()

        repository.recordManualSession(manualRecord)
        assertEquals(1, repository.portalHistory.value.size)
        assertTrue(repository.portalHistory.value[0].isManual)

        // Portal sync runs and server now includes the record
        repository.syncPortalHistory("user", "pass", force = true)

        // The record should now be reconciled to the server record (isManual == false)
        val reconciledHistory = repository.portalHistory.value
        assertEquals(1, reconciledHistory.size)
        assertFalse(reconciledHistory[0].isManual)
        assertEquals("VIT-Vellore", reconciledHistory[0].location)
        assertEquals("9e:85:16:b5:22:eb", reconciledHistory[0].macAddress)

        // In database too
        val entities = database.statsDao().getAllPortalSessions().first()
        assertEquals(1, entities.size)
        assertFalse(entities[0].isManual)
    }
}
