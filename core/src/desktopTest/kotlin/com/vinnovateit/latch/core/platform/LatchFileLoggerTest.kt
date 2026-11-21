package com.vinnovateit.latch.core.platform

import com.vinnovateit.latch.core.mp.JFile
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class LatchFileLoggerTest {
    @Test
    fun testFileLoggerWritesEntriesToFile() {
        val tempDir = createTempDirectory("logger_test").toFile()
        val logFile = File(tempDir, "test_log.txt")
        val logger = LatchFileLogger(JFile(logFile.path))

        logger.d("TEST_TAG", "Debug message 123")
        logger.w("TEST_TAG", "Warning message 456")
        logger.e("TEST_TAG", "Error message 789")

        assertTrue(logFile.exists(), "Log file should exist")
        val content = logFile.readText()
        assertTrue(content.contains("[DEBUG] [TEST_TAG] Debug message 123"))
        assertTrue(content.contains("[WARN] [TEST_TAG] Warning message 456"))
        assertTrue(content.contains("[ERROR] [TEST_TAG] Error message 789"))
        tempDir.deleteRecursively()
    }

    @Test
    fun testFileLoggerRotatesWhenMaxSizeExceeded() {
        val tempDir = createTempDirectory("logger_rot_test").toFile()
        val logFile = File(tempDir, "test_log.txt")
        val logger = LatchFileLogger(JFile(logFile.path), maxSizeBytes = 50L)

        logger.d("TEST", "This is a long line that exceeds fifty bytes definitely")
        logger.d("TEST", "Second line after rotation")

        val backupFile = File(tempDir, "test_log.txt.old")
        assertTrue(backupFile.exists(), "Backup file should exist after rotation")
        val currentContent = logFile.readText()
        assertTrue(currentContent.contains("Second line after rotation"))
        tempDir.deleteRecursively()
    }

    @Test
    fun testCompositeLoggerDispatchesToAllLoggers() {
        val tempDir = createTempDirectory("composite_test").toFile()
        val logFile1 = File(tempDir, "log1.txt")
        val logFile2 = File(tempDir, "log2.txt")
        val composite = CompositeLogger(listOf(LatchFileLogger(JFile(logFile1.path)), LatchFileLogger(JFile(logFile2.path))))

        composite.d("COMP", "Dispatched to both")
        assertTrue(logFile1.readText().contains("Dispatched to both"))
        assertTrue(logFile2.readText().contains("Dispatched to both"))
        tempDir.deleteRecursively()
    }
}
