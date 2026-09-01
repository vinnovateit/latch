package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Formatter as JulFormatter

/**
 * Rotating file logger.
 *
 * This is not optional on desktop: the app starts hidden at login and has no
 * console, so this file is the only channel for diagnosing a failed latch. Also
 * echoes to stdout when running under `gradle run`.
 */
class FileLogger(
    logsDir: File,
    private val echoToStdout: Boolean = false,
) : Logger {

    private var handler: FileHandler? = null

    private val julLogger: java.util.logging.Logger? = try {
        java.util.logging.Logger.getLogger("latch").apply {
            useParentHandlers = false
            level = Level.ALL
            // 5MB x 5 files, append.
            val fileHandler =
                FileHandler(File(logsDir, "latch.%g.log").absolutePath, 5_000_000, 5, true)
            fileHandler.level = Level.ALL
            fileHandler.formatter = object : JulFormatter() {
                private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                override fun format(record: LogRecord): String =
                    "${stamp.format(Date(record.millis))} [${record.level.name.take(1)}] [${Thread.currentThread().name}] ${record.message}\n"
            }
            // Replace any handler left over from a previous FileLogger in the same
            // JVM (e.g. a test) so lines are not written twice.
            handlers.forEach { removeHandler(it) }
            addHandler(fileHandler)
            handler = fileHandler
        }
    } catch (e: Throwable) {
        // Never let logging setup prevent the app from running.
        null
    }

    private fun write(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val line = "[$tag] $message"
        julLogger?.log(level, line)
        throwable?.let { julLogger?.log(level, it.stackTraceToString()) }
        // Flush on every write. FileHandler buffers, and a tray app is routinely
        // killed without a clean shutdown -- unflushed records would mean the log
        // is empty exactly when it is needed for support.
        runCatching { handler?.flush() }
        if (echoToStdout) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            println("$now [${level.name.take(1)}] [${Thread.currentThread().name}] $line")
            throwable?.printStackTrace()
        }
    }

    override fun d(tag: String, message: String) = write(Level.FINE, tag, message)
    override fun w(tag: String, message: String) = write(Level.WARNING, tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) =
        write(Level.SEVERE, tag, message, throwable)
}
