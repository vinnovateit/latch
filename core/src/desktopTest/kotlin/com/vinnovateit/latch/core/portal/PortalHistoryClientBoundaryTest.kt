package com.vinnovateit.latch.core.portal

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.NoOpLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOST = "136.233.9.110"
private const val PORTAL_FORM = """<form name="chooseAuthForm" action="/registration/chooseAuth.do;jsessionid=abc"></form>"""

class PortalHistoryClientBoundaryTest {

    private class Reply(val code: Int = 200, val body: String = "", val location: String? = null)

    private class Sent(val url: URL, val conn: ScriptedConnection)

    private class ScriptedConnection(url: URL, private val reply: Reply, private val cookie: String?) : HttpURLConnection(url) {
        val sentBody = ByteArrayOutputStream()
        val sentCookie: String? get() = getRequestProperty("Cookie")
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sentBody
        override fun getInputStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray())
        override fun getResponseCode() = reply.code
        override fun getHeaderField(name: String?): String? = when {
            name.equals("Set-Cookie", ignoreCase = true) -> cookie
            name.equals("Location", ignoreCase = true) -> reply.location
            else -> null
        }
    }

    private class Portal(val route: (URL) -> Reply) : HttpTransport {
        val sent = mutableListOf<Sent>()
        override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection =
            ScriptedConnection(url, route(url), "JSESSIONID=abc; path=/").also { sent += Sent(url, it) }

        val passwordSentTo get() = sent.filter { "loginPassword" in it.conn.sentBody.toString() }.map { it.url.host }
    }

    private fun fetch(portal: Portal) = PortalHistoryClient(portal, NoOpLogger).fetchHistory("24BDS0155", "secret")

    @Test
    fun `the password goes only to the portal's own form`() {
        val portal = Portal { url -> if ("Main.jsp" in url.path) Reply(body = PORTAL_FORM) else Reply() }

        assertTrue(fetch(portal).isSuccess)

        assertEquals(listOf(HOST), portal.passwordSentTo)
    }

    @Test
    fun `a form that posts to another host gets nothing`() {
        val portal = Portal { Reply(body = """<form action="http://attacker.example/chooseAuth.do"></form>""") }

        assertTrue(fetch(portal).isFailure)

        assertEquals(emptyList(), portal.passwordSentTo)
    }

    @Test
    fun `a page with no login form gets nothing`() {
        val portal = Portal { Reply(body = "<html>Welcome to the hotel router</html>") }

        assertTrue(fetch(portal).isFailure)

        assertEquals(emptyList(), portal.passwordSentTo)
    }

    @Test
    fun `a first page that is not a plain 200 gets nothing`() {
        val portal = Portal { Reply(code = 302, body = PORTAL_FORM, location = "http://attacker.example/") }

        assertTrue(fetch(portal).isFailure)

        assertEquals(emptyList(), portal.passwordSentTo)
    }

    @Test
    fun `a redirect off the portal is refused and never receives the session cookie`() {
        val portal = Portal { url ->
            when {
                "Main.jsp" in url.path -> Reply(body = PORTAL_FORM)
                "customerSessionHistory" in url.path -> Reply(code = 302, location = "http://attacker.example/steal")
                else -> Reply()
            }
        }

        assertTrue(fetch(portal).isFailure)

        assertFalse(portal.sent.any { it.url.host == "attacker.example" }, "the request must not leave the portal")
    }

    @Test
    fun `a redirect within the portal is followed with the cookie`() {
        val portal = Portal { url ->
            when {
                "Main.jsp" in url.path -> Reply(body = PORTAL_FORM)
                "customerSessionHistory" in url.path -> Reply(code = 302, location = "/registration/moved")
                else -> Reply()
            }
        }

        assertTrue(fetch(portal).isSuccess)

        val moved = portal.sent.single { url -> url.url.path == "/registration/moved" }
        assertEquals("JSESSIONID=abc", moved.conn.sentCookie)
        assertEquals("GET", moved.conn.requestMethod)
    }

    @Test
    fun `an endless redirect stops`() {
        val portal = Portal { url ->
            if ("Main.jsp" in url.path) Reply(body = PORTAL_FORM) else if ("chooseAuth" in url.path) Reply() else Reply(code = 302, location = "/again")
        }

        assertTrue(fetch(portal).isFailure)

        assertTrue(portal.sent.size < 10)
    }

    @Test
    fun `form actions resolve against the portal page`() {
        val page = URL("http://$HOST/registration/Main.jsp?wispId=1")

        assertEquals("http://$HOST/registration/chooseAuth.do", loginFormAction("""action="chooseAuth.do"""", page).toString())
        assertEquals("http://$HOST/x/chooseAuth.do", loginFormAction("""action="http://$HOST/x/chooseAuth.do"""", page).toString())
        assertNull(loginFormAction("""action="https://$HOST/chooseAuth.do"""", page), "a different scheme is a different site")
        assertNull(loginFormAction("""action="http://$HOST:8080/chooseAuth.do"""", page), "so is a different port")
        assertNull(loginFormAction("""action="//attacker.example/chooseAuth.do"""", page))
        assertNull(loginFormAction("""action="http://$HOST@attacker.example/chooseAuth.do"""", page))
    }
}
