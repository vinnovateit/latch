package com.vinnovateit.latch.core.wifi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalFingerprintTest {

    @Test
    fun `a redirect to the Pronto host is VIT's portal`() {
        assertTrue(pointsAtPronto("http://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com", null))
        assertTrue(pointsAtPronto("HTTP://PHC.PRONTONETWORKS.COM/", null))
    }

    @Test
    fun `a page that redirects to the Pronto host is VIT's portal`() {
        val page = """<meta http-equiv="refresh" content="0;url=http://phc.prontonetworks.com/cgi-bin/authlogin">"""
        assertTrue(pointsAtPronto(null, page))
    }

    @Test
    fun `other portals are not`() {
        assertFalse(pointsAtPronto("http://login.cafe.example/portal", "<html>Welcome</html>"))
        assertFalse(pointsAtPronto(null, null))
    }

    @Test
    fun `a host that only starts with the Pronto host is not`() {
        assertFalse(pointsAtPronto("http://phc.prontonetworks.com.example/login", null))
        assertFalse(pointsAtPronto("http://example.com/?next=phc.prontonetworks.com", null))
    }

    @Test
    fun `a relative or malformed redirect is not`() {
        assertFalse(pointsAtPronto("/cgi-bin/authlogin", null))
        assertFalse(pointsAtPronto("not a url", null))
    }
}
