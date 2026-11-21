package com.vinnovateit.latch.core.platform

import com.vinnovateit.latch.core.mp.JFile as File
import com.vinnovateit.latch.core.mp.JFileWriter
import com.vinnovateit.latch.core.mp.JPrintWriter
import com.vinnovateit.latch.core.mp.KSimpleDateFormat as SimpleDateFormat
import com.vinnovateit.latch.core.mp.KDate as Date
import com.vinnovateit.latch.core.mp.KLocale as Locale
import com.vinnovateit.latch.core.mp.useOutput
import com.vinnovateit.latch.core.mp.usePrint
import com.vinnovateit.latch.core.mp.withLock
import com.vinnovateit.latch.core.mp.printStackTraceTo

class LatchFileLogger(
    val logFile: File,
    private val maxSizeBytes: Long = 5 * 1024 * 1024L
) : Logger {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val parent = logFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (logFile.exists() && logFile.length() > maxSizeBytes) {
                val backup = if (parent != null) File(parent, "${logFile.name}.old") else File("${logFile.name}.old")
                if (backup.exists()) backup.delete()
                logFile.renameTo(backup)
            }
            val timestamp = withLock(dateFormat) { dateFormat.format(Date()) }
            JFileWriter(logFile, true).useOutput { fw ->
                JPrintWriter(fw).usePrint { pw ->
                    pw.println("$timestamp [$level] [$tag] $message")
                    throwable?.let { t ->
                        t.printStackTraceTo(pw)
                    }
                }
            }
        } catch (ignored: Throwable) {}
    }

    override fun d(tag: String, message: String) = log("DEBUG", tag, message)
    override fun w(tag: String, message: String) = log("WARN", tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) = log("ERROR", tag, message, throwable)
}

class CompositeLogger(private val loggers: List<Logger>) : Logger {
    override fun d(tag: String, message: String) = loggers.forEach { it.d(tag, message) }
    override fun w(tag: String, message: String) = loggers.forEach { it.w(tag, message) }
    override fun e(tag: String, message: String, throwable: Throwable?) = loggers.forEach { it.e(tag, message, throwable) }
}

val Platform.logger: Logger
    get() = if (Platform.isInstalled) Platform.services.logger else NoOpLogger
