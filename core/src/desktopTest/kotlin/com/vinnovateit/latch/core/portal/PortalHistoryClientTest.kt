package com.vinnovateit.latch.core.portal

import com.vinnovateit.latch.core.mp.JHttpConnection
import com.vinnovateit.latch.core.mp.JUrl
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortalHistoryClientTest {

    private class FakeHttpConnection(url: URL, val responseHtml: String, val setCookieHeader: String? = null) : HttpURLConnection(url) {
        private val outputStream = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = outputStream
        override fun getInputStream(): InputStream = ByteArrayInputStream(responseHtml.toByteArray())
        override fun getResponseCode(): Int = 200
        override fun getHeaderField(name: String?): String? {
            if (name.equals("Set-Cookie", ignoreCase = true)) return setCookieHeader
            return null
        }
    }

    private class FakeHttpTransport(val mainHtml: String, val loginHtml: String, val historyHtml: String) : HttpTransport {
        override fun open(url: JUrl, handle: NetworkHandle?): JHttpConnection =
        JHttpConnection(openLegacy(URL(url.toString()), handle))

    private fun openLegacy(url: URL, handle: NetworkHandle?): HttpURLConnection {

            val urlStr = url.toString()
            return when {
                "Main.jsp" in urlStr -> FakeHttpConnection(url, mainHtml, "JSESSIONID=test-session-id; path=/registration")
                "chooseAuth.do" in urlStr -> FakeHttpConnection(url, loginHtml)
                "customerSessionHistory.do" in urlStr || "CustomerSessionHistory.jsp" in urlStr -> FakeHttpConnection(url, historyHtml)
                else -> FakeHttpConnection(url, "")
            }
            }
    }

    @Test
    fun testComputeDateFilter() {
        val client = PortalHistoryClient(FakeHttpTransport("", "", ""))
        val filter24 = client.computeDateFilter("24BDS0155")
        assertEquals("2024", filter24.startYear)
        assertEquals("00", filter24.startMonth)
        assertEquals("01", filter24.startDay)

        val filterInvalid = client.computeDateFilter("unknown_user")
        assertEquals("2024", filterInvalid.startYear)
        assertEquals("00", filterInvalid.startMonth)
    }

    @Test
    fun testFetchHistorySuccess() {
        val mainHtml = """<form name="chooseAuthForm" action="/registration/chooseAuth.do;jsessionid=test-session-id"></form>"""
        val loginHtml = "<html>Logged in successfully</html>"
        val historyHtml = """
            <html>
            <table border="1">
                <tr bgcolor="#DDDDDD">
                    <td>VIT-Vellore</td>
                    <td>9e:85:16:b5:22:eb</td>
                    <td>09/07/26 11:39:30 PM</td>
                    <td>09/07/26 11:53:16 PM</td>
                    <td>13 min 46 sec</td>
                    <td>15.15 KB</td>
                    <td>116.02 KB</td>
                    <td>131.17 KB</td>
                </tr>
            </table>
            </html>
        """.trimIndent()

        val transport = FakeHttpTransport(mainHtml, loginHtml, historyHtml)
        val client = PortalHistoryClient(transport)

        val result = client.fetchHistory("24BDS0155", "zero")
        assertTrue(result.isSuccess)
        val list = result.getOrNull()!!
        assertEquals(1, list.size)
        assertEquals("VIT-Vellore", list[0].location)
    }
}
