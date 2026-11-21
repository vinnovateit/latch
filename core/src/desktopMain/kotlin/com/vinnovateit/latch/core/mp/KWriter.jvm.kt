package com.vinnovateit.latch.core.mp

import java.io.BufferedWriter
import java.io.OutputStream

actual class KWriter(private val delegate: BufferedWriter) {
    actual fun write(text: String) = delegate.write(text)
    actual fun flush() = delegate.flush()
    actual fun close() = delegate.close()
}

actual fun wrapReportSink(stream: Any): KWriter = KWriter((stream as OutputStream).bufferedWriter())
