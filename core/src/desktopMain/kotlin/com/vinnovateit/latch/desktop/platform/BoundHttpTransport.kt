package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * An [HttpTransport] whose connections leave from the Wi-Fi interface's address.
 *
 * macOS routes a socket bound to an interface's address over that interface
 * (scoped routing), even when Ethernet or a VPN tunnel holds the default
 * route. Binding keeps the probe, the login and the history sync on the
 * Wi-Fi network they are meant for. [localAddress] answers null when there is
 * no Wi-Fi address, and the connection then takes the default route, as
 * [DesktopHttpTransport] does.
 *
 * HttpURLConnection has no hook for the socket it opens. A bound connection
 * is therefore a [BoundHttpURLConnection].
 */
class BoundHttpTransport(
    private val localAddress: (NetworkHandle?) -> InetAddress?,
) : HttpTransport {
    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val address = localAddress(handle) ?: return url.openConnection() as HttpURLConnection
        return BoundHttpURLConnection(url, address)
    }
}

/**
 * A small HTTP/1.1 client behind the HttpURLConnection API, sending from
 * [localAddress].
 *
 * It covers what Latch's callers use: GET and POST with a buffered body,
 * request headers, redirects followed or not, Content-Length, chunked and
 * read-to-close bodies, error streams and timeouts. Every request is sent
 * with `Connection: close` on a fresh socket, and nothing is cached.
 */
internal class BoundHttpURLConnection(
    url: URL,
    private val localAddress: InetAddress,
) : HttpURLConnection(url) {

    private class Response(
        val statusLine: String,
        val code: Int,
        val message: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private val requestBody = ByteArrayOutputStream()
    private var requestHeaders: Map<String, List<String>> = emptyMap()
    private var response: Response? = null
    @Volatile private var socket: Socket? = null

    override fun usingProxy(): Boolean = false

    /** Fixes the request headers. The request itself goes out when the response is first asked for. */
    override fun connect() {
        if (connected) return
        if (url.protocol != "http" && url.protocol != "https") throw ProtocolException("Unsupported protocol: ${url.protocol}")
        // URLConnection only hands out its request headers before it is connected.
        requestHeaders = requestProperties
        connected = true
    }

    override fun disconnect() {
        try {
            socket?.close()
        } catch (_: IOException) {}
    }

    override fun getOutputStream(): OutputStream {
        if (!doOutput) throw ProtocolException("cannot write to a URLConnection if doOutput=false")
        if (response != null) throw ProtocolException("Cannot write output after reading input.")
        connect()
        return requestBody
    }

    override fun getResponseCode(): Int = exchange().code

    override fun getResponseMessage(): String = exchange().message

    override fun getHeaderField(name: String?): String? = name?.let { exchange().header(it) }

    override fun getHeaderFieldKey(n: Int): String? = if (n == 0) null else exchange().headers.getOrNull(n - 1)?.first

    override fun getHeaderField(n: Int): String? =
        if (n == 0) exchange().statusLine else exchange().headers.getOrNull(n - 1)?.second

    override fun getHeaderFields(): Map<String?, List<String>> {
        val current = exchange()
        val fields = LinkedHashMap<String?, MutableList<String>>()
        fields[null] = mutableListOf(current.statusLine)
        current.headers.forEach { (key, value) -> fields.getOrPut(key) { mutableListOf() } += value }
        return fields
    }

    override fun getInputStream(): InputStream {
        val current = exchange()
        if (current.code >= 400) {
            if (current.code == 404 || current.code == 410) throw FileNotFoundException(url.toString())
            throw IOException("Server returned HTTP response code: ${current.code} for URL: $url")
        }
        return ByteArrayInputStream(current.body)
    }

    override fun getErrorStream(): InputStream? =
        response?.takeIf { it.code >= 400 }?.let { ByteArrayInputStream(it.body) }

    private fun exchange(): Response {
        response?.let { return it }
        connect()
        var target = url
        var verb = if (doOutput && method == "GET") "POST" else method
        var body: ByteArray? = if (doOutput) requestBody.toByteArray() else null
        repeat(MAX_REDIRECTS + 1) {
            val answer = send(target, verb, body)
            val location = answer.header("Location")
            if (!instanceFollowRedirects || answer.code !in REDIRECT_CODES || location == null) {
                response = answer
                return answer
            }
            val next = URL(target, location)
            // Like HttpURLConnection, never follow a redirect to another protocol.
            if (next.protocol != target.protocol) {
                response = answer
                return answer
            }
            if (answer.code in 301..303) {
                verb = "GET"
                body = null
            }
            target = next
        }
        throw ProtocolException("Server redirected too many times ($MAX_REDIRECTS)")
    }

    private fun send(target: URL, verb: String, body: ByteArray?): Response {
        val host = target.host
        val port = if (target.port != -1) target.port else target.defaultPort
        // A socket bound to an IPv4 address can only reach IPv4 addresses.
        val remote = InetAddress.getAllByName(host).firstOrNull { (it is Inet4Address) == (localAddress is Inet4Address) }
            ?: throw ConnectException("$host has no address reachable from ${localAddress.hostAddress}")

        val raw = Socket()
        socket = raw
        try {
            raw.bind(InetSocketAddress(localAddress, 0))
            raw.connect(InetSocketAddress(remote, port), connectTimeout)
            raw.soTimeout = readTimeout
            val stream = if (target.protocol == "https") secure(raw, host, port) else raw
            val out = stream.getOutputStream()
            out.write(requestHead(target, verb, body).toByteArray(Charsets.ISO_8859_1))
            body?.let { out.write(it) }
            out.flush()
            return readResponse(BufferedInputStream(stream.getInputStream()), verb)
        } finally {
            try {
                raw.close()
            } catch (_: IOException) {}
        }
    }

    private fun secure(raw: Socket, host: String, port: Int): Socket {
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
        return tls
    }

    private fun requestHead(target: URL, verb: String, body: ByteArray?): String = buildString {
        val path = target.file.ifEmpty { "/" }
        append("$verb $path HTTP/1.1\r\n")
        val defaultPort = target.port == -1 || target.port == target.defaultPort
        append("Host: ${target.host}${if (defaultPort) "" else ":${target.port}"}\r\n")
        append("Connection: close\r\n")
        requestHeaders.forEach { (name, values) ->
            if (name.equals("Host", true) || name.equals("Connection", true) || name.equals("Content-Length", true)) return@forEach
            values.forEach { value ->
                // A header value carrying a line break would let it inject headers of its own.
                if (name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
                    throw IllegalArgumentException("Illegal character in header $name")
                }
                append("$name: $value\r\n")
            }
        }
        if (body != null) append("Content-Length: ${body.size}\r\n")
        append("\r\n")
    }

    private fun readResponse(input: InputStream, verb: String): Response {
        while (true) {
            val statusLine = readLine(input) ?: throw IOException("Connection closed before a response from $url")
            val parts = statusLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull()
            if (!parts[0].startsWith("HTTP/") || code == null) throw ProtocolException("Invalid status line: $statusLine")

            val headers = mutableListOf<Pair<String, String>>()
            while (true) {
                val line = readLine(input) ?: throw IOException("Connection closed inside the response headers")
                if (line.isEmpty()) break
                if (headers.size >= MAX_HEADERS) throw ProtocolException("Too many response headers")
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            // 1xx answers are interim. The real response follows on the same stream.
            if (code in 100..199) continue

            fun header(name: String) = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
            val body = when {
                verb == "HEAD" || code == 204 || code == 304 -> ByteArray(0)
                header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                else -> header("Content-Length")?.toLongOrNull()
                    ?.let { readExactly(input, it) }
                    ?: readToEnd(input)
            }
            return Response(statusLine, code, parts.getOrElse(2) { "" }, headers, body)
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("Connection closed inside a chunked body")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw ProtocolException("Invalid chunk size: $sizeLine")
            if (size == 0L) {
                // Trailer headers, if any, end with an empty line.
                while (readLine(input)?.isNotEmpty() == true) Unit
                return out.toByteArray()
            }
            if (out.size() + size > MAX_BODY_BYTES) throw IOException("Response body exceeds $MAX_BODY_BYTES bytes")
            out.write(readExactly(input, size))
            readLine(input)
        }
    }

    private fun readExactly(input: InputStream, length: Long): ByteArray {
        if (length > MAX_BODY_BYTES) throw IOException("Response body exceeds $MAX_BODY_BYTES bytes")
        val buffer = ByteArray(length.toInt())
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) throw IOException("Connection closed after $total of $length body bytes")
            total += read
        }
        return buffer
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BODY_BYTES) throw IOException("Response body exceeds $MAX_BODY_BYTES bytes")
            out.write(buffer, 0, read)
        }
    }

    /** One CRLF- or LF-terminated line, without its terminator. Null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            if (line.size() >= MAX_LINE_BYTES) throw ProtocolException("Response line exceeds $MAX_LINE_BYTES bytes")
            line.write(byte)
        }
        return line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
        const val MAX_HEADERS = 200
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 4L * 1024 * 1024
    }
}
