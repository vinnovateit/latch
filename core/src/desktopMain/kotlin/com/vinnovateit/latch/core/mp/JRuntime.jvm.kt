package com.vinnovateit.latch.core.mp

actual class JUrl actual constructor(spec: String) {
    val delegate: java.net.URL = java.net.URL(spec)
    actual val host: String get() = delegate.host
    actual override fun toString(): String = delegate.toString()
}

actual class JHttpConnection internal constructor(val delegate: java.net.HttpURLConnection) {
    actual constructor(url: JUrl) : this(java.net.URL(url.toString()).openConnection() as java.net.HttpURLConnection)

    actual companion object {
        actual const val HTTP_OK = java.net.HttpURLConnection.HTTP_OK
        const val HTTP_MOVED_PERM = java.net.HttpURLConnection.HTTP_MOVED_PERM
        const val HTTP_MOVED_TEMP = java.net.HttpURLConnection.HTTP_MOVED_TEMP
    }

    actual var connectTimeout: Int
        get() = delegate.connectTimeout
        set(value) { delegate.connectTimeout = value }
    actual var readTimeout: Int
        get() = delegate.readTimeout
        set(value) { delegate.readTimeout = value }
    actual var instanceFollowRedirects: Boolean
        get() = delegate.instanceFollowRedirects
        set(value) { delegate.instanceFollowRedirects = value }
    actual var requestMethod: String
        get() = delegate.requestMethod
        set(value) { delegate.requestMethod = value }
    actual var doOutput: Boolean
        get() = delegate.doOutput
        set(value) { delegate.doOutput = value }
    actual var useCaches: Boolean
        get() = delegate.useCaches
        set(value) { delegate.useCaches = value }

    actual val outputStream: JOutputStream get() = JOutputStream(delegate.outputStream)
    actual val inputStream: JInputStream? get() = delegate.inputStream?.let(::JInputStream)
    actual val errorStream: JInputStream? get() = delegate.errorStream?.let(::JInputStream)
    actual val responseCode: Int get() = delegate.responseCode

    actual fun setRequestProperty(key: String, value: String) = delegate.setRequestProperty(key, value)
    actual fun getHeaderField(name: String): String? = delegate.getHeaderField(name)
    actual fun connect() = delegate.connect()
    actual fun disconnect() = delegate.disconnect()
}

actual class JInputStream internal constructor(val delegate: java.io.InputStream) {
    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
    actual fun bufferedReader(): JBufferedReader = JBufferedReader(delegate.bufferedReader())
    actual fun close() = delegate.close()
}

actual class JBufferedReader internal constructor(val delegate: java.io.BufferedReader) {
    actual fun readText(): String = delegate.readText()
    actual fun close() = delegate.close()
}

actual open class JOutputStream internal constructor(val delegate: java.io.OutputStream) {
    actual open fun write(bytes: ByteArray) = delegate.write(bytes)
    actual open fun bufferedWriter(): JBufferedWriter = JBufferedWriter(delegate.bufferedWriter())
    actual open fun close() = delegate.close()
}

actual class JBufferedWriter internal constructor(val delegate: java.io.BufferedWriter) {
    actual fun write(text: String) = delegate.write(text)
    actual fun close() = delegate.close()
}

actual class JFile actual constructor(actual val path: String, child: String?) {
    actual constructor(parent: JFile, child: String) : this("${parent.path}/$child", null)
    private val delegate: java.io.File get() = java.io.File(path)
    actual val name: String get() = delegate.name
    actual val parentFile: JFile? get() = delegate.parentFile?.let { JFile(it.path) }
    actual fun exists(): Boolean = delegate.exists()
    actual val isDirectory: Boolean get() = delegate.isDirectory
    actual fun length(): Long = delegate.length()
    actual fun mkdirs(): Boolean = delegate.mkdirs()
    actual fun delete(): Boolean = delegate.delete()
    actual fun renameTo(target: JFile): Boolean = delegate.renameTo(java.io.File(target.path))
    actual fun outputStream(): JOutputStream = JOutputStream(java.io.FileOutputStream(path))
}

actual class JFileWriter actual constructor(file: JFile, append: Boolean) : JOutputStream(java.io.FileOutputStream(file.path, append))

actual class JPrintWriter actual constructor(out: JOutputStream) {
    val delegate: java.io.PrintWriter = java.io.PrintWriter(out.delegate)
    actual fun println(text: String) = delegate.println(text)
    actual fun close() = delegate.close()
}

actual open class MPIOException actual constructor(message: String?) : java.io.IOException(message)
actual class JUnknownHostException actual constructor(message: String?) : MPIOException(message)
actual class JSslException actual constructor(message: String?) : MPIOException(message)

actual fun Throwable.printStackTraceTo(writer: JPrintWriter) = printStackTrace(writer.delegate)
actual fun <R> withLock(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)
actual fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
