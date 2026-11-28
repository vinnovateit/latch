@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.mp

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import platform.posix.FILE
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rename
import platform.posix.stat
import platform.posix.S_IFDIR
import platform.posix.system
import platform.posix.unlink
import platform.posix.getenv
import kotlinx.cinterop.toKString

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

internal open class NativeSink {
    open fun write(bytes: ByteArray) {}
    open fun close() {}
}

internal class NativeOutputStream : NativeSink() {
    val data = mutableListOf<Byte>()
    override fun write(bytes: ByteArray) { data.addAll(bytes.asList()) }
}

internal class NativeFileSink(path: String, append: Boolean) : NativeSink() {
    private val handle: CPointer<FILE> = fopen(path, if (append) "a" else "w")
        ?: throw MPIOException("Cannot open $path")
    private var closed = false
    override fun write(bytes: ByteArray) {
        if (bytes.isNotEmpty()) fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), handle)
    }
    override fun close() {
        // Match java.io.OutputStream.close(): idempotent.
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

private val winHttpCounter = kotlin.concurrent.AtomicInt(0)

private data class CurlResult(val status: Int, val body: ByteArray, val headers: Map<String, String>)

/**
 * HTTP through the OS curl.exe.
 *
 * The Linux native build links libcurl, but no curl cinterop is available for
 * mingwX64. Every request is assembled into a batch file so quoting stays
 * correct on Windows (paths under %LOCALAPPDATA% contain spaces), then curl
 * writes the body and headers to files and the status code to stdout.
 *
 * Portal requests must never leave on the default route: if no Wi-Fi handle
 * was bound and the endpoint is not explicitly public, fail closed.
 */
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
    actual fun getHeaderField(name: String): String? = result().headers[name.lowercase()]
    actual val inputStream: JInputStream?
        get() = result().let { if (it.status in 200..399) JInputStream(NativeInputStream(it.body)) else null }
    actual val errorStream: JInputStream?
        get() = result().let { if (it.status !in 200..399) JInputStream(NativeInputStream(it.body)) else null }
    actual fun disconnect() { cached = null }

    private fun result(): CurlResult {
        cached?.let { return it }
        val iface = boundInterface
        if (iface == null && !publicEndpoint) {
            throw MPIOException("No Wi-Fi handle: refusing to send portal traffic on the default route")
        }

        val tmp = winTempDir()
        val stem = "latch-http-${getpid()}-${winHttpCounter.addAndGet(1)}"
        val bat = "$tmp\\$stem.bat"
        val bodyFile = "$tmp\\$stem.body"
        val headerFile = "$tmp\\$stem.head"
        val codeFile = "$tmp\\$stem.code"
        val dataFile = "$tmp\\$stem.data"

        val args = mutableListOf<String>()
        args += "curl.exe"
        args += "-sS"
        args += "-D \"$headerFile\""
        args += "-o \"$bodyFile\""
        if (instanceFollowRedirects) args += "-L"
        if (connectTimeout > 0) args += "--connect-timeout ${(connectTimeout + 999) / 1000}"
        if (readTimeout > 0) args += "--max-time ${(readTimeout + 999) / 1000}"
        if (requestMethod != "GET") args += "-X $requestMethod"
        for ((k, v) in properties) args += "-H \"${k.replace("\"", "\\\"")}: ${v.replace("\"", "\\\"")}\""
        val bytes = requestBody.data.toByteArray()
        if (doOutput || bytes.isNotEmpty()) {
            JFile(dataFile).outputStream().useOutput { it.write(bytes) }
            args += "--data-binary \"@$dataFile\""
        }
        // --interface pins portal credentials to Wi-Fi. Windows libcurl needs
        // an IPv4 address here, not the adapter name ("Wi-Fi" fails with
        // CURLE_INTERFACE_FAILED / exit 45). Resolve the name to its IP first.
        if (iface != null) {
            val ip = com.vinnovateit.latch.desktop.platform.runCommand(
                "netsh interface ipv4 show config name=\"$iface\"",
            ).lineSequence()
                .firstOrNull { it.contains("IP Address", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
                ?: throw MPIOException("No IPv4 address on Wi-Fi interface $iface; refusing default route")
            args += "--interface $ip"
        }
        args += "\"$spec\""
        args += "-w \"%%{http_code}\""
        args += "> \"$codeFile\""

        val script = buildString {
            append("@echo off\r\n")
            append(args.joinToString(" "))
            append("\r\n")
        }
        JFile(bat).outputStream().useOutput { it.write(script.encodeToByteArray()) }

        val exit = runHidden("cmd /c call \"$bat\" >nul 2>&1")
        val status = runCatching { readAll(codeFile).trim().toInt() }.getOrDefault(0)
        val headerText = runCatching { readAll(headerFile) }.getOrDefault("")
        val body = runCatching { readAllBytes(bodyFile) }.getOrDefault(ByteArray(0))
        listOf(bat, bodyFile, headerFile, codeFile, dataFile).forEach { runCatching { unlink(it) } }

        if (exit != 0 || status == 0) throw MPIOException("curl request failed: exit=$exit status=$status iface=${iface ?: "default"}")

        val headers = mutableMapOf<String, String>()
        // curl terminates the last header block with a blank line, so splitting
        // on "\n\n" yields a trailing EMPTY block. Taking lastOrNull() blindly
        // dropped every header, including Set-Cookie, making credential
        // validation always return 302. Use the last non-empty block.
        headerText.replace("\r\n", "\n").split("\n\n")
            .lastOrNull { it.isNotBlank() }?.lines()?.forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        cached = CurlResult(status, body, headers)
        return cached!!
    }
}

internal fun winTempDir(): String {
    val explicit = listOf("LATCH_TMP", "TEMP", "TMP").firstNotNullOfOrNull { getenv(it)?.toKString()?.takeIf(String::isNotBlank) }
    val dir = explicit ?: (listOf("USERPROFILE", "HOMEDRIVE").firstNotNullOfOrNull { getenv(it)?.toKString() } ?: "C:") + "\\Temp"
    JFile(dir).mkdirs()
    return dir.trimEnd('\\')
}

private fun readAll(path: String): String = readAllBytes(path).decodeToString()

private fun readAllBytes(path: String): ByteArray {
    val f = fopen(path, "rb") ?: return ByteArray(0)
    val out = mutableListOf<Byte>()
    try {
        val buffer = ByteArray(8192)
        while (true) {
            val fread = platform.posix.fread(buffer.refTo(0), 1u, buffer.size.toULong(), f).toInt()
            if (fread <= 0) break
            out.addAll(buffer.copyOf(fread).asList())
        }
    } finally { fclose(f) }
    return out.toByteArray()
}

private val separatorRegex = Regex("[/\\\\]")

private fun joinWindowsPath(parent: String, child: String?): String {
    if (child.isNullOrEmpty()) return parent
    val trimmedChild = child.trimStart('/', '\\')
    if (parent.isEmpty()) return trimmedChild
    return "${parent.trimEnd('/', '\\')}\\$trimmedChild"
}

actual class JFile actual constructor(path: String, child: String?) {
    actual val path: String = joinWindowsPath(path, child)
    actual constructor(parent: JFile, child: String) : this(parent.path, child)
    actual val name: String get() = path.split(separatorRegex).last { it.isNotEmpty() }
    actual val parentFile: JFile? get() {
        val parts = path.split(separatorRegex)
        return if (parts.size <= 1) null else JFile(parts.dropLast(1).joinToString("\\"))
    }
    actual fun exists(): Boolean = access(path, 0) == 0
    actual val isDirectory: Boolean get() = kotlinx.cinterop.memScoped {
        val st = alloc<platform.posix.stat>()
        platform.posix.stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFDIR) != 0
    }
    actual fun length(): Long = kotlinx.cinterop.memScoped {
        val st = alloc<platform.posix.stat>()
        if (platform.posix.stat(path, st.ptr) != 0) 0L else st.st_size.toLong()
    }
    actual fun mkdirs(): Boolean {
        if (isDirectory) return true
        val normalized = path.replace('/', '\\').trimEnd('\\')
        if (normalized.isEmpty()) return false
        val parts = normalized.split('\\').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        var current = ""
        for ((index, segment) in parts.withIndex()) {
            current = when {
                index == 0 && segment.length == 2 && segment[1] == ':' -> segment
                current.isEmpty() -> if (normalized.startsWith("\\\\")) "\\\\$segment" else segment
                else -> "$current\\$segment"
            }
            if (index == 0 && segment.length == 2 && segment[1] == ':') continue
            if (access(current, 0) != 0) {
                mkdir(current)
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
    actual fun println(text: String) { out.write((text + "\r\n").encodeToByteArray()) }
    actual fun close() = out.close()
}

actual fun Throwable.printStackTraceTo(writer: JPrintWriter) {
    writer.println(toString())
}
