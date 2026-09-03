package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.data.Session
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.platform.UserNotifier
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class DesktopEngineRuntimeTest {
    @Test
    fun `runtime builds one shared graph and closes idempotently`() = runBlocking {
        val directory = createTempDirectory("latch-engine-").toFile()
        val previous = System.getProperty("latch.dataDir")
        try {
            System.setProperty("latch.dataDir", directory.absolutePath)
            val runtime = DesktopEngineRuntime.create(NoOpNotifier, echoLogsToStdout = false)

            runtime.start()
            runtime.start()
            runtime.database.statsDao().insertSession(Session(startTime = 1, endTime = 2, rxBytes = 3, txBytes = 4, maxRxBps = 5, maxTxBps = 6))

            runtime.close()
            runtime.close()
            assertTrue(runtime.isClosed)
            assertFalse(runtime.engine.submitAndAwait(LatchCommand.Shutdown, timeoutMs = 100))

            val reopened = DesktopEngineRuntime.create(NoOpNotifier, echoLogsToStdout = false)
            val rows = reopened.database.statsDao().getAllSessions().first()
            assertEquals(1, rows.size)
            assertEquals(3, rows.single().rxBytes)
            reopened.close()
        } finally {
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }

}

private object NoOpNotifier : UserNotifier {
    override fun showOngoing(title: String, text: String) = Unit
    override fun notifyTransient(title: String, text: String, isError: Boolean) = Unit
    override fun hideOngoing() = Unit
}
