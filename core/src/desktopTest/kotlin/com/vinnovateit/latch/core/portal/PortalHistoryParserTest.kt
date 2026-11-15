package com.vinnovateit.latch.core.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortalHistoryParserTest {

    private val sampleHtml = """
        <html>
        <body>
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
            <tr bgcolor="#F3F3F3">
                <td>VIT-Vellore</td>
                <td>9e:85:16:b5:22:eb</td>
                <td>09/07/26 09:49:28 PM</td>
                <td>09/07/26 09:49:38 PM</td>
                <td>10 sec</td>
                <td>--</td>
                <td>--</td>
                <td>--</td>
            </tr>
        </table>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun testParseBytes() {
        assertEquals(0L, PortalHistoryParser.parseBytes("--"))
        assertEquals(0L, PortalHistoryParser.parseBytes(""))
        assertEquals(1024L, PortalHistoryParser.parseBytes("1.00 KB"))
        assertEquals(1048576L, PortalHistoryParser.parseBytes("1.00 MB"))
        assertEquals(1073741824L, PortalHistoryParser.parseBytes("1.00 GB"))
        assertEquals(15513L, PortalHistoryParser.parseBytes("15.15 KB"))
    }

    @Test
    fun testParseDate() {
        val millis = PortalHistoryParser.parseDate("09/07/26 11:39:30 PM")
        assertTrue(millis > 0)
    }

    @Test
    fun `each fallback format parses its own dates`() {
        val expected = java.time.LocalDateTime.of(2024, 1, 15, 10, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, PortalHistoryParser.parseDate("01/15/24 10:00:00 AM"))
        assertEquals(expected, PortalHistoryParser.parseDate("01/15/2024 10:00:00 AM"))
        assertEquals(expected, PortalHistoryParser.parseDate("15-01-2024 10:00:00"))
        assertEquals(expected, PortalHistoryParser.parseDate("2024-01-15 10:00:00"))
    }

    @Test
    fun `an impossible date is not rolled over into another one`() {
        assertEquals(0L, PortalHistoryParser.parseDate("13/45/24 10:00:00 AM"))
    }

    @Test
    fun testParseHtml() {
        val records = PortalHistoryParser.parse(sampleHtml)
        assertEquals(2, records.size)

        val first = records[0]
        assertEquals("VIT-Vellore", first.location)
        assertEquals("9e:85:16:b5:22:eb", first.macAddress)
        assertEquals("13 min 46 sec", first.durationFormatted)
        assertEquals(15513L, first.uploadBytes)
        assertEquals(118804L, first.downloadBytes)
        assertEquals(134318L, first.totalBytes)
        assertTrue(first.logoutTime >= first.loginTime)

        val second = records[1]
        assertEquals("10 sec", second.durationFormatted)
        assertEquals(0L, second.uploadBytes)
        assertEquals(0L, second.downloadBytes)
        assertEquals(0L, second.totalBytes)
    }
}
