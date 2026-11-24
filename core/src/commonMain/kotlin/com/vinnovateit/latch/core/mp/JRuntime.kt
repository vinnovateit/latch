package com.vinnovateit.latch.core.mp

/** Multiplatform equivalents of the small `java.*` surface the shared code uses. */

expect class JUrl(spec: String) {
    val host: String
    override fun toString(): String
}

expect class JHttpConnection(url: JUrl) {
    var connectTimeout: Int
    var readTimeout: Int
    var instanceFollowRedirects: Boolean
    var requestMethod: String
    var doOutput: Boolean
    var useCaches: Boolean
    val outputStream: JOutputStream
    val inputStream: JInputStream?
    val errorStream: JInputStream?
    val responseCode: Int
    fun setRequestProperty(key: String, value: String)
    fun getHeaderField(name: String): String?
    /** Pin this request to a specific network interface (Wi-Fi only). */
    fun bindToInterface(interfaceName: String)
    /** Explicitly allow the default route — only for public endpoints. */
    fun allowDefaultRoute()
    fun connect()
    fun disconnect()
    companion object { val HTTP_OK: Int }
}

expect class JInputStream {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun bufferedReader(): JBufferedReader
    fun close()
}

expect class JBufferedReader {
    fun readText(): String
    fun close()
}

expect open class JOutputStream {
    open fun write(bytes: ByteArray)
    open fun bufferedWriter(): JBufferedWriter
    open fun close()
}

expect class JBufferedWriter {
    fun write(text: String)
    fun close()
}

expect class JFile(path: String, child: String? = null) {
    constructor(parent: JFile, child: String)
    val path: String
    val name: String
    val parentFile: JFile?
    fun exists(): Boolean
    val isDirectory: Boolean
    fun length(): Long
    fun mkdirs(): Boolean
    fun delete(): Boolean
    fun renameTo(target: JFile): Boolean
    fun outputStream(): JOutputStream
}

expect class JFileWriter(file: JFile, append: Boolean = false) : JOutputStream

expect class JPrintWriter(out: JOutputStream) {
    fun println(text: String)
    fun close()
}

expect fun Throwable.printStackTraceTo(writer: JPrintWriter)

expect open class MPIOException(message: String? = null) : Exception
expect class JUnknownHostException(message: String? = null) : MPIOException
expect class JSslException(message: String? = null) : MPIOException

expect fun <R> withLock(lock: Any, block: () -> R): R

expect fun urlEncode(value: String): String

inline fun <R> JInputStream.useStream(block: (JInputStream) -> R): R { try { return block(this) } finally { close() } }
inline fun <R> JOutputStream.useOutput(block: (JOutputStream) -> R): R { try { return block(this) } finally { close() } }
inline fun <R> JBufferedReader.useReader(block: (JBufferedReader) -> R): R { try { return block(this) } finally { close() } }
inline fun <R> JBufferedWriter.useWriter(block: (JBufferedWriter) -> R): R { try { return block(this) } finally { close() } }
inline fun <R> JPrintWriter.usePrint(block: (JPrintWriter) -> R): R { try { return block(this) } finally { close() } }
