@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.mp

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.refTo
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

actual class KWriter(private val path: String) {
    private val handle: CPointer<FILE> = fopen(path, "w")
        ?: error("Cannot open report file: $path")

    actual fun write(text: String) {
        val bytes = text.encodeToByteArray()
        if (bytes.isNotEmpty()) fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), handle)
    }

    actual fun flush() { fflush(handle) }
    actual fun close() { fclose(handle) }
}

actual fun wrapReportSink(stream: Any): KWriter = KWriter(stream as String)
