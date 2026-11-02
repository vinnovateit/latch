package com.vinnovateit.latch.desktop.platform

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundHttpTransportTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    /** Serves [responses] in order, one connection each, and records every raw request. */
    private inner class ScriptedServer(vararg responses: String) {
        private val server = ServerSocket(0, 50, loopback)
        val requests = CopyOnWriteArrayList<String>()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                try {
                    responses.forEach { response ->
                        server.accept().use { socket ->
                            requests += readRequest(socket.getInputStream())
                            socket.getOutputStream().apply {
                                write(response.toByteArray(Charsets.ISO_8859_1))
                                flush()
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Closed by the test before every response was asked for.
                }
            }
        }

        fun close() = server.close()

        private fun readRequest(input: InputStream): String {
            val head = ByteArrayOutputStream()
            while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) break
                head.write(byte)
            }
            val text = head.toString(Charsets.ISO_8859_1.name())
            val length = Regex("(?im)^Content-Length: (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            return text + String(ByteArray(length) { input.read().toByte() }, Charsets.ISO_8859_1)
        }
    }

    private val servers = mutableListOf<ScriptedServer>()

    private fun serve(vararg responses: String) = ScriptedServer(*responses).also { servers += it }

    @AfterTest
    fun closeServers() = servers.forEach { it.close() }

    private fun open(server: ScriptedServer, path: String = "/"): HttpURLConnection =
        BoundHttpTransport { loopback }.open(URL("http://localhost:${server.port}$path"), null)

    @Test
    fun `posts the body with its headers and reads a sized response`() {
        val server = serve("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nSet-Cookie: a=1; Path=/\r\n\r\nhello")
        val connection = open(server, "/cgi-bin/authlogin?URI=http://example.com")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        connection.outputStream.bufferedWriter().use { it.write("userId=a&password=b") }

        assertEquals(200, connection.responseCode)
        assertEquals("hello", connection.inputStream.bufferedReader().readText())
        assertEquals("a=1; Path=/", connection.getHeaderField("set-cookie"))
        val request = server.requests.single()
        assertTrue(request.startsWith("POST /cgi-bin/authlogin?URI=http://example.com HTTP/1.1\r\n"), request)
        assertTrue("User-Agent: Mozilla/5.0 (Android)\r\n" in request, request)
        assertTrue("Content-Length: 19\r\n" in request, request)
        assertTrue(request.endsWith("\r\n\r\nuserId=a&password=b"), request)
    }

    @Test
    fun `reads a chunked response`() {
        val server = serve("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5;ext=1\r\npedia\r\n0\r\n\r\n")
        val connection = open(server)

        assertEquals("Wikipedia", connection.inputStream.bufferedReader().readText())
    }

    @Test
    fun `reads a body that ends when the server closes`() {
        val server = serve("HTTP/1.0 200 OK\r\n\r\nuntil close")

        assertEquals("until close", open(server).inputStream.bufferedReader().readText())
    }

    @Test
    fun `leaves a redirect alone unless asked to follow it`() {
        val server = serve("HTTP/1.1 302 Found\r\nLocation: http://phc.prontonetworks.com/\r\nContent-Length: 0\r\n\r\n")
        val connection = open(server, "/generate_204")
        connection.instanceFollowRedirects = false

        assertEquals(302, connection.responseCode)
        assertEquals("http://phc.prontonetworks.com/", connection.getHeaderField("Location"))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `follows a redirect after a POST with a GET`() {
        val server = serve(
            "HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndone",
        )
        val connection = open(server, "/start")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.instanceFollowRedirects = true
        connection.outputStream.use { it.write("x=1".toByteArray()) }

        assertEquals("done", connection.inputStream.bufferedReader().readText())
        assertTrue(server.requests[1].startsWith("GET /next HTTP/1.1\r\n"), server.requests[1])
    }

    @Test
    fun `an error response throws from the input stream and has an error stream`() {
        val server = serve("HTTP/1.1 404 Not Found\r\nContent-Length: 7\r\n\r\nmissing")
        val connection = open(server)

        assertEquals(404, connection.responseCode)
        assertFailsWith<FileNotFoundException> { connection.inputStream }
        assertEquals("missing", connection.errorStream!!.bufferedReader().readText())
    }

    @Test
    fun `a successful response has no error stream`() {
        val connection = open(serve("HTTP/1.1 204 No Content\r\n\r\n"))

        assertEquals(204, connection.responseCode)
        assertNull(connection.errorStream)
    }

    @Test
    fun `a header value with a line break is refused`() {
        val server = serve("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        val connection = open(server)
        connection.setRequestProperty("Cookie", "a=1\r\nX-Injected: yes")

        assertFailsWith<IllegalArgumentException> { connection.responseCode }
    }

    @Test
    fun `a connection really is bound to the given address`() {
        val server = serve("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        // TEST-NET-1: never assigned to this machine, so binding to it must fail.
        val unowned = InetAddress.getByName("192.0.2.1")
        val connection = BoundHttpTransport { unowned }.open(URL("http://localhost:${server.port}/"), null)

        assertFailsWith<IOException> { connection.responseCode }
    }

    @Test
    fun `without an address the default route is used`() {
        val connection = BoundHttpTransport { null }.open(URL("http://localhost:1/"), null)

        assertTrue(connection !is BoundHttpURLConnection)
    }
}
