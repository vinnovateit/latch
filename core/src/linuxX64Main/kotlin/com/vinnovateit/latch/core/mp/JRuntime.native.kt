@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.mp

import curl.CURLE_OK
import curl.CURLOPT_CONNECTTIMEOUT_MS
import curl.CURLOPT_CUSTOMREQUEST
import curl.CURLOPT_FOLLOWLOCATION
import curl.CURLOPT_HEADERDATA
import curl.CURLOPT_HEADERFUNCTION
import curl.CURLOPT_HTTPHEADER
import curl.CURLOPT_INTERFACE
import curl.CURLOPT_COPYPOSTFIELDS
import curl.CURLOPT_TIMEOUT_MS
import curl.CURLOPT_URL
import curl.CURLOPT_WRITEDATA
import curl.CURLOPT_WRITEFUNCTION
import curl.CURLINFO_RESPONSE_CODE
import curl.curl_easy_cleanup
import curl.curl_easy_getinfo
import curl.curl_easy_init
import curl.curl_easy_perform
import curl.curl_easy_setopt
import curl.curl_easy_strerror
import curl.curl_slist
import curl.curl_slist_append
import curl.curl_slist_free_all
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import kotlinx.cinterop.toKString
import kotlinx.cinterop.staticCFunction
import platform.posix.FILE
import platform.posix.S_IFDIR
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink


actual open class MPIOException actual constructor(message: String?) : Exception(message)
actual class JUnknownHostException actual constructor(message: String?) : MPIOException(message)
actual class JSslException actual constructor(message: String?) : MPIOException(message)

actual class JUrl actual constructor(spec: String) {
    val spec: String = spec
    actual val host: String get() = spec.substringAfter("://").substringBefore('/').substringBefore(':')
    actual override fun toString(): String = spec
}

actual fun urlEncode(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        when {
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch == '-' || ch == '_' || ch == '.' || ch == '~' -> append(ch)
            ch == ' ' -> append('+')
            else -> { append('%'); append(hex[c shr 4]); append(hex[c and 0xF]) }
        }
    }
}

private val compatLock = kotlin.concurrent.AtomicInt(0)
actual fun <R> withLock(lock: Any, block: () -> R): R {
    while (!compatLock.compareAndSet(0, 1)) {}
    try { return block() } finally { compatLock.value = 0 }
}

class NativeInputStream(private val data: ByteArray) {
    private var pos = 0
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(length, data.size - pos)
        data.copyInto(buffer, offset, pos, pos + n)
        pos += n
        return n
    }
    fun readText(): String = data.decodeToString()
}

actual class JInputStream internal constructor(private val source: NativeInputStream) {
    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)
    actual fun bufferedReader(): JBufferedReader = JBufferedReader(source)
    actual fun close() {}
}

actual class JBufferedReader internal constructor(private val source: NativeInputStream) {
    actual fun readText(): String = source.readText()
    actual fun close() {}
}

/** Sink behind [JOutputStream]; file-backed for writers, in-memory for HTTP requests. */
internal open class NativeSink {
    open fun write(bytes: ByteArray) {}
    open fun close() {}
}

internal class NativeOutputStream : NativeSink() {
    val data = mutableListOf<Byte>()
    override fun write(bytes: ByteArray) { data.addAll(bytes.asList()) }
    fun text(): String = data.toByteArray().decodeToString()
}

internal class NativeFileSink(path: String, append: Boolean) : NativeSink() {
    private val handle: CPointer<FILE> = fopen(path, if (append) "a" else "w")
        ?: throw MPIOException("Cannot open $path")
    private var closed = false
    override fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), handle)
    }
    override fun close() {
        // fclose is not idempotent; JPrintWriter.close() and the surrounding
        // useOutput{} both close the same sink. Java's OutputStream.close() is
        // idempotent, so mirror that here.
        if (!closed) { closed = true; fclose(handle) }
    }
}

actual open class JOutputStream internal constructor(private val target: NativeSink) {
    actual open fun write(bytes: ByteArray) = target.write(bytes)
    actual open fun bufferedWriter(): JBufferedWriter = JBufferedWriter(target)
    actual open fun close() = target.close()
}

actual class JBufferedWriter internal constructor(private val target: NativeSink) {
    actual fun write(text: String) = target.write(text.encodeToByteArray())
    actual fun close() = target.close()
}

private data class CurlResult(val status: Int, val body: ByteArray, val headers: Map<String, String>)

actual class JHttpConnection actual constructor(url: JUrl) {
    private val spec = url.spec
    actual companion object { actual const val HTTP_OK = 200 }

    actual var connectTimeout: Int = 0
    actual var readTimeout: Int = 0
    actual var instanceFollowRedirects: Boolean = true
    actual var requestMethod: String = "GET"
    actual var doOutput: Boolean = false
    actual var useCaches: Boolean = true

    private val properties = mutableMapOf<String, String>()
    private val requestBody = NativeOutputStream()
    private var cached: CurlResult? = null
    private var boundInterface: String? = null
    private var publicEndpoint = false

    actual val outputStream: JOutputStream get() = JOutputStream(requestBody)

    actual fun setRequestProperty(key: String, value: String) { properties[key] = value }
    actual fun bindToInterface(interfaceName: String) { boundInterface = interfaceName }
    actual fun allowDefaultRoute() { publicEndpoint = true }
    actual fun connect() { result() }

    actual val responseCode: Int get() = result().status
    // Header names are stored lowercased; callers use Java's canonical casing
    // ("Set-Cookie", "Location"). Match case-insensitively like HttpURLConnection.
    actual fun getHeaderField(name: String): String? = result().headers[name.lowercase()]
    actual val inputStream: JInputStream?
        get() = result().let { if (it.status in 200..399) JInputStream(NativeInputStream(it.body)) else null }
    actual val errorStream: JInputStream?
        get() = result().let { if (it.status !in 200..399) JInputStream(NativeInputStream(it.body)) else null }
    actual fun disconnect() { cached = null }

    private fun result(): CurlResult {
        cached?.let { return it }
        val body = StringBuilder()
        val headers = StringBuilder()
        val writeCb = staticCFunction { ptr: CPointer<ByteVar>?, size: ULong, nmemb: ULong, userdata: CPointer<CPointed>? ->
            val n = (size * nmemb).toInt()
            userdata?.asStableRef<StringBuilder>()?.get()?.append(ptr?.readBytes(n)?.decodeToString().orEmpty())
            size * nmemb
        }
        val headerCb = staticCFunction { ptr: CPointer<ByteVar>?, size: ULong, nmemb: ULong, userdata: CPointer<CPointed>? ->
            val n = (size * nmemb).toInt()
            userdata?.asStableRef<StringBuilder>()?.get()?.append(ptr?.readBytes(n)?.decodeToString().orEmpty())
            size * nmemb
        }
        val handle = curl_easy_init() ?: throw MPIOException("curl init failed")
        try {
            memScoped {
                val bodyRef = StableRef.create(body)
                val headerRef = StableRef.create(headers)
                var slist: CPointer<curl_slist>? = null
                try {
                    curl_easy_setopt(handle, CURLOPT_URL, spec)
                    // Pin to Wi-Fi. Never silently fall back to the default route:
                    // with Ethernet plugged in that would send portal credentials
                    // off-network in plaintext (the captive portal only answers on
                    // the Wi-Fi interface).
                    val iface = boundInterface
                    if (iface == null && !publicEndpoint) {
                        throw MPIOException("No Wi-Fi handle: refusing to send portal traffic on the default route")
                    }
                    if (iface != null) curl_easy_setopt(handle, CURLOPT_INTERFACE, iface)
                    curl_easy_setopt(handle, CURLOPT_FOLLOWLOCATION, if (instanceFollowRedirects) 1L else 0L)
                    if (connectTimeout > 0) curl_easy_setopt(handle, CURLOPT_CONNECTTIMEOUT_MS, connectTimeout.toLong())
                    if (readTimeout > 0) curl_easy_setopt(handle, CURLOPT_TIMEOUT_MS, readTimeout.toLong())
                    if (requestMethod != "GET") curl_easy_setopt(handle, CURLOPT_CUSTOMREQUEST, requestMethod)
                    val bytes = requestBody.data.toByteArray()
                    // COPYPOSTFIELDS makes libcurl copy the bytes immediately.
                    // Plain POSTFIELDS keeps only the pointer, and the temporary
                    // Kotlin string buffer can be collected before curl_easy_perform
                    // — silently corrupting the credential POST body.
                    if (doOutput || bytes.isNotEmpty()) curl_easy_setopt(handle, CURLOPT_COPYPOSTFIELDS, bytes.toKString())
                    for ((k, v) in properties) slist = curl_slist_append(slist, "$k: $v")
                    if (slist != null) curl_easy_setopt(handle, CURLOPT_HTTPHEADER, slist)
                    curl_easy_setopt(handle, CURLOPT_WRITEFUNCTION, writeCb)
                    curl_easy_setopt(handle, CURLOPT_WRITEDATA, bodyRef.asCPointer())
                    curl_easy_setopt(handle, CURLOPT_HEADERFUNCTION, headerCb)
                    curl_easy_setopt(handle, CURLOPT_HEADERDATA, headerRef.asCPointer())
                    val curlCode = curl_easy_perform(handle)
                    if (curlCode != CURLE_OK) {
                        val detail = curl_easy_strerror(curlCode)?.toKString().orEmpty()
                        throw MPIOException("curl request failed: code=$curlCode $detail iface=${boundInterface ?: "default"}")
                    }
                    val status = alloc<LongVar>()
                    curl_easy_getinfo(handle, CURLINFO_RESPONSE_CODE, status.ptr)
                    val parsed = mutableMapOf<String, String>()
                    headers.toString().lines().forEach { line ->
                        val i = line.indexOf(':')
                        if (i > 0) parsed[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    cached = CurlResult(status.value.toInt(), body.toString().encodeToByteArray(), parsed)
                } finally {
                    slist?.let { curl_slist_free_all(it) }
                    bodyRef.dispose(); headerRef.dispose()
                }
            }
        } finally {
            curl_easy_cleanup(handle)
        }
        return cached!!
    }
}

actual class JFile actual constructor(actual val path: String, child: String?) {
    actual constructor(parent: JFile, child: String) : this("${parent.path}/$child", null)
    actual val name: String get() = path.substringAfterLast('/')
    actual val parentFile: JFile? get() {
        val i = path.lastIndexOf('/')
        return if (i <= 0) null else JFile(path.substring(0, i))
    }
    actual fun exists(): Boolean = access(path, 0) == 0
    actual val isDirectory: Boolean get() = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFDIR) != 0
    }
    actual fun length(): Long = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) 0L else st.st_size.toLong()
    }
    actual fun mkdirs(): Boolean { mkdir(path, 0x1FFu); return true }
    actual fun delete(): Boolean = unlink(path) == 0
    actual fun renameTo(target: JFile): Boolean = rename(path, target.path) == 0
    actual fun outputStream(): JOutputStream = JOutputStream(NativeFileSink(path, false))
}

actual class JFileWriter actual constructor(file: JFile, append: Boolean) :
    JOutputStream(NativeFileSink(file.path, append))

actual class JPrintWriter actual constructor(private val out: JOutputStream) {
    actual fun println(text: String) { out.write((text + "\n").encodeToByteArray()) }
    actual fun close() = out.close()
}

actual fun Throwable.printStackTraceTo(writer: JPrintWriter) {
    writer.println(toString())
}
