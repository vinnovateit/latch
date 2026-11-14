package com.vinnovateit.latch.platform

import com.vinnovateit.latch.core.platform.android.cleanAndroidSsid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidSsidTest {

    @Test
    fun aReadableNameLosesItsQuotes() {
        assertEquals("G-VIT 5", cleanAndroidSsid("\"G-VIT 5\""))
    }

    @Test
    fun aWithheldNameIsUnknownRatherThanANetworkName() {
        assertNull(cleanAndroidSsid("<unknown ssid>"))
        assertNull(cleanAndroidSsid(null))
        assertNull(cleanAndroidSsid(""))
        assertNull(cleanAndroidSsid("\"\""))
    }
}
