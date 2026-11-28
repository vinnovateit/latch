@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.mp

import curl.CURLE_ABORTED_BY_CALLBACK
import curl.CURLE_COULDNT_RESOLVE_HOST
import curl.CURLE_COULDNT_RESOLVE_PROXY
import curl.CURLE_OK
import curl.CURLE_PEER_FAILED_VERIFICATION
import curl.CURLE_SSL_CACERT_BADFILE
import curl.CURLE_SSL_CERTPROBLEM
import curl.CURLE_SSL_CIPHER
import curl.CURLE_SSL_CLIENTCERT
import curl.CURLE_SSL_CONNECT_ERROR
import curl.CURLE_SSL_CRL_BADFILE
import curl.CURLE_SSL_ENGINE_INITFAILED
import curl.CURLE_SSL_ENGINE_NOTFOUND
import curl.CURLE_SSL_ENGINE_SETFAILED
import curl.CURLE_SSL_INVALIDCERTSTATUS
import curl.CURLE_SSL_ISSUER_ERROR
import curl.CURLE_SSL_PINNEDPUBKEYNOTMATCH
import curl.CURLE_SSL_SHUTDOWN_FAILED
import curl.CURLINFO_RESPONSE_CODE
import curl.CURLMSG
import curl.CURLMcode
import curl.CURLM_OK
import curl.CURLOPT_CONNECTTIMEOUT_MS
import curl.CURLOPT_COPYPOSTFIELDS
import curl.CURLOPT_CUSTOMREQUEST
import curl.CURLOPT_FOLLOWLOCATION
import curl.CURLOPT_HEADERDATA
import curl.CURLOPT_HEADERFUNCTION
import curl.CURLOPT_HTTPHEADER
import curl.CURLOPT_INTERFACE
import curl.CURLOPT_NOSIGNAL
import curl.CURLOPT_POSTFIELDSIZE
import curl.CURLOPT_TIMEOUT_MS
import curl.CURLOPT_URL
import curl.CURLOPT_WRITEDATA
import curl.CURLOPT_WRITEFUNCTION
import curl.CURL_GLOBAL_ALL
import curl.CURLcode
import curl.curl_easy_cleanup
import curl.curl_easy_init
import curl.curl_easy_getinfo
import curl.curl_easy_setopt
import curl.curl_easy_strerror
import curl.curl_global_init
import curl.curl_multi_add_handle
import curl.curl_multi_cleanup
import curl.curl_multi_info_read
import curl.curl_multi_init
import curl.curl_multi_perform
import curl.curl_multi_poll
import curl.curl_multi_remove_handle
import curl.curl_multi_strerror
import curl.curl_multi_wakeup
import curl.curl_slist
import curl.curl_slist_append
import curl.curl_slist_free_all
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import platform.posix.EEXIST
import platform.posix.FILE
import platform.posix.S_IFDIR
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink
import platform.posix.usleep


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

private val compatLock = AtomicInt(0)
actual fun <R> withLock(lock: Any, block: () -> R): R {
    while (!compatLock.compareAndSet(0, 1)) {}
    try { return block() } finally { compatLock.value = 0 }
}

private val curlInitState = AtomicInt(0)
private fun ensureCurlInitialized() {
    if (curlInitState.value == 2) return
    if (curlInitState.compareAndSet(0, 1)) {
        curl_global_init(CURL_GLOBAL_ALL.toLong())
        curlInitState.value = 2
    } else {
        while (curlInitState.value != 2) {
            usleep(100u)
        }
    }
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
    fun toByteArray(): ByteArray = data.toByteArray()
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
    @Volatile private var aborted = false
    private val multiLock = AtomicInt(0)
    private var activeMulti: COpaquePointer? = null

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

    actual fun disconnect() {
        aborted = true
        cached = null
        withMultiLock {
            activeMulti?.let { curl_multi_wakeup(it) }
        }
    }

    private inline fun <R> withMultiLock(block: () -> R): R {
        while (!multiLock.compareAndSet(0, 1)) {}
        return try { block() } finally { multiLock.value = 0 }
    }

    private fun result(): CurlResult {
        cached?.let { return it }
        if (aborted) throw MPIOException("curl request aborted")
        ensureCurlInitialized()

        val bodyBuffer = NativeOutputStream()
        val headerBuffer = NativeOutputStream()
        val writeCb = staticCFunction { ptr: CPointer<ByteVar>?, size: ULong, nmemb: ULong, userdata: CPointer<CPointed>? ->
            val n = (size * nmemb).toInt()
            if (n > 0 && ptr != null && userdata != null) {
                userdata.asStableRef<NativeOutputStream>().get().write(ptr.readBytes(n))
            }
            size * nmemb
        }
        val headerCb = staticCFunction { ptr: CPointer<ByteVar>?, size: ULong, nmemb: ULong, userdata: CPointer<CPointed>? ->
            val n = (size * nmemb).toInt()
            if (n > 0 && ptr != null && userdata != null) {
                userdata.asStableRef<NativeOutputStream>().get().write(ptr.readBytes(n))
            }
            size * nmemb
        }

        val handle = curl_easy_init() ?: throw MPIOException("curl init failed")
        val multi = curl_multi_init()
        if (multi == null) {
            curl_easy_cleanup(handle)
            throw MPIOException("curl multi init failed")
        }
        var addedToMulti = false
        try {
            memScoped {
                val bodyRef = StableRef.create(bodyBuffer)
                val headerRef = StableRef.create(headerBuffer)
                var slist: CPointer<curl_slist>? = null
                try {
                    val iface = boundInterface
                    if (iface == null && !publicEndpoint) {
                        throw MPIOException("No Wi-Fi handle: refusing to send portal traffic on the default route")
                    }

                    curl_easy_setopt(handle, CURLOPT_NOSIGNAL, 1L)
                    curl_easy_setopt(handle, CURLOPT_URL, spec)
                    if (iface != null) curl_easy_setopt(handle, CURLOPT_INTERFACE, iface)
                    curl_easy_setopt(handle, CURLOPT_FOLLOWLOCATION, if (instanceFollowRedirects) 1L else 0L)
                    if (connectTimeout > 0) curl_easy_setopt(handle, CURLOPT_CONNECTTIMEOUT_MS, connectTimeout.toLong())
                    if (readTimeout > 0) curl_easy_setopt(handle, CURLOPT_TIMEOUT_MS, readTimeout.toLong())
                    if (requestMethod != "GET") curl_easy_setopt(handle, CURLOPT_CUSTOMREQUEST, requestMethod)

                    val bytes = requestBody.toByteArray()
                    if (doOutput || bytes.isNotEmpty()) {
                        curl_easy_setopt(handle, CURLOPT_POSTFIELDSIZE, bytes.size.toLong())
                        if (bytes.isEmpty()) {
                            curl_easy_setopt(handle, CURLOPT_COPYPOSTFIELDS, "")
                        } else {
                            curl_easy_setopt(handle, CURLOPT_COPYPOSTFIELDS, bytes.refTo(0))
                        }
                    }

                    for ((k, v) in properties) slist = curl_slist_append(slist, "$k: $v")
                    if (slist != null) curl_easy_setopt(handle, CURLOPT_HTTPHEADER, slist)
                    curl_easy_setopt(handle, CURLOPT_WRITEFUNCTION, writeCb)
                    curl_easy_setopt(handle, CURLOPT_WRITEDATA, bodyRef.asCPointer())
                    curl_easy_setopt(handle, CURLOPT_HEADERFUNCTION, headerCb)
                    curl_easy_setopt(handle, CURLOPT_HEADERDATA, headerRef.asCPointer())

                    val addCode = curl_multi_add_handle(multi, handle)
                    if (addCode != CURLM_OK) {
                        val detail = curl_multi_strerror(addCode)?.toKString().orEmpty()
                        throw MPIOException("curl multi add failed: code=$addCode $detail")
                    }
                    addedToMulti = true
                    withMultiLock { activeMulti = multi }

                    val configuredTimeoutMs = maxOf(readTimeout.toLong(), connectTimeout.toLong())
                    val hardDeadlineNs = if (configuredTimeoutMs > 0L) {
                        nanoTime() + (configuredTimeoutMs + 500L) * 1_000_000L
                    } else {
                        Long.MAX_VALUE
                    }

                    val stillRunning = alloc<IntVar>()
                    var transferResult: CURLcode? = null
                    while (true) {
                        if (aborted) {
                            throw mapCurlFailure(CURLE_ABORTED_BY_CALLBACK, boundInterface)
                        }
                        if (nanoTime() > hardDeadlineNs) {
                            throw MPIOException(
                                "curl request timed out after ${configuredTimeoutMs}ms iface=${boundInterface ?: "default"}",
                            )
                        }
                        val multiCode: CURLMcode = curl_multi_perform(multi, stillRunning.ptr)
                        if (multiCode != CURLM_OK) {
                            val detail = curl_multi_strerror(multiCode)?.toKString().orEmpty()
                            throw MPIOException("curl multi perform failed: code=$multiCode $detail")
                        }

                        val msgsLeft = alloc<IntVar>()
                        while (true) {
                            val msg = curl_multi_info_read(multi, msgsLeft.ptr) ?: break
                            if (msg.pointed.msg == CURLMSG.CURLMSG_DONE) {
                                transferResult = msg.pointed.data.result
                            }
                        }

                        if (stillRunning.value == 0 || transferResult != null) break

                        val remainingMs = if (hardDeadlineNs == Long.MAX_VALUE) {
                            100
                        } else {
                            ((hardDeadlineNs - nanoTime()) / 1_000_000L).coerceIn(10L, 100L).toInt()
                        }
                        curl_multi_poll(multi, null, 0u, remainingMs, null)
                    }

                    val curlCode = transferResult ?: CURLE_OK
                    if (curlCode != CURLE_OK) {
                        throw mapCurlFailure(curlCode, boundInterface)
                    }

                    val status = alloc<LongVar>()
                    curl_easy_getinfo(handle, CURLINFO_RESPONSE_CODE, status.ptr)
                    val parsed = mutableMapOf<String, String>()
                    headerBuffer.text()
                        .replace("\r\n", "\n")
                        .split("\n\n")
                        .lastOrNull { it.isNotBlank() }
                        ?.lines()
                        ?.forEach { line ->
                            val i = line.indexOf(':')
                            if (i > 0) parsed[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                        }
                    cached = CurlResult(status.value.toInt(), bodyBuffer.toByteArray(), parsed)
                } finally {
                    withMultiLock { activeMulti = null }
                    if (addedToMulti) curl_multi_remove_handle(multi, handle)
                    slist?.let { curl_slist_free_all(it) }
                    bodyRef.dispose(); headerRef.dispose()
                }
            }
        } finally {
            curl_multi_cleanup(multi)
            curl_easy_cleanup(handle)
        }
        return cached!!
    }
}

private fun mapCurlFailure(curlCode: CURLcode, boundInterface: String?): MPIOException {
    val detail = curl_easy_strerror(curlCode)?.toKString().orEmpty()
    val summary = "curl request failed: code=$curlCode $detail iface=${boundInterface ?: "default"}"
    return when (curlCode) {
        CURLE_COULDNT_RESOLVE_HOST,
        CURLE_COULDNT_RESOLVE_PROXY,
        -> JUnknownHostException(summary)
        CURLE_SSL_CONNECT_ERROR,
        CURLE_PEER_FAILED_VERIFICATION,
        CURLE_SSL_ENGINE_NOTFOUND,
        CURLE_SSL_ENGINE_SETFAILED,
        CURLE_SSL_CERTPROBLEM,
        CURLE_SSL_CIPHER,
        CURLE_SSL_ENGINE_INITFAILED,
        CURLE_SSL_CACERT_BADFILE,
        CURLE_SSL_SHUTDOWN_FAILED,
        CURLE_SSL_CRL_BADFILE,
        CURLE_SSL_ISSUER_ERROR,
        CURLE_SSL_PINNEDPUBKEYNOTMATCH,
        CURLE_SSL_INVALIDCERTSTATUS,
        CURLE_SSL_CLIENTCERT,
        -> JSslException(summary)
        else -> MPIOException(summary)
    }
}

private fun joinPosixPath(parent: String, child: String?): String {
    if (child.isNullOrEmpty()) return parent
    val trimmedChild = child.trimStart('/')
    if (parent.isEmpty()) return trimmedChild
    val trimmedParent = parent.trimEnd('/')
    return if (trimmedParent.isEmpty()) "/$trimmedChild" else "$trimmedParent/$trimmedChild"
}

actual class JFile actual constructor(path: String, child: String?) {
    actual val path: String = joinPosixPath(path, child)
    actual constructor(parent: JFile, child: String) : this(parent.path, child)
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
    actual fun mkdirs(): Boolean {
        if (isDirectory) return true
        val normalized = path.trimEnd('/')
        if (normalized.isEmpty()) return false
        var current = if (normalized.startsWith('/')) "/" else ""
        for (segment in normalized.split('/')) {
            if (segment.isEmpty()) continue
            current = if (current.isEmpty()) segment else if (current == "/") "/$segment" else "$current/$segment"
            if (access(current, 0) != 0) {
                if (mkdir(current, 0x1FFu) != 0 && errno != EEXIST) {
                    return false
                }
            }
        }
        return isDirectory
    }
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
