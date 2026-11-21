package com.vinnovateit.latch.core.mp

/** Multiplatform sink used by the HTML report generator. */
expect class KWriter {
    fun write(text: String)
    fun flush()
    fun close()
}

/** JVM: [stream] is a java.io.OutputStream. Native: [stream] is an absolute file path. */
expect fun wrapReportSink(stream: Any): KWriter
