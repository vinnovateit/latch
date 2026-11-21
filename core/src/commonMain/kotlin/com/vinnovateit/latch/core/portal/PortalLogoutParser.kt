package com.vinnovateit.latch.core.portal
import com.vinnovateit.latch.core.mp.nowMillis

import com.vinnovateit.latch.core.model.PortalSessionRecord

object PortalLogoutParser {
    private val TIME_SPENT_REGEX = Regex("""Time\s+Spent\s*:\s*(\d+)\s*H\s*:\s*(\d+)\s*M\s*:\s*(\d+)\s*S""", RegexOption.IGNORE_CASE)
    private val BYTES_SENT_REGEX = Regex("""Bytes\s+Sent\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val BYTES_RECEIVED_REGEX = Regex("""Bytes\s+Received\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(html: String, logoutTimeMillis: Long = nowMillis()): PortalSessionRecord? {
        val timeMatch = TIME_SPENT_REGEX.find(html) ?: return null

        val hours = timeMatch.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val minutes = timeMatch.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val seconds = timeMatch.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L

        val durationMillis = (hours * 3600L + minutes * 60L + seconds) * 1000L

        val uploadBytes = BYTES_SENT_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val downloadBytes = BYTES_RECEIVED_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val durationFormatted = com.vinnovateit.latch.core.stats.formatDurationWords(durationMillis)
        val loginTime = (logoutTimeMillis - durationMillis).coerceAtLeast(0L)

        return PortalSessionRecord(
            location = "Portal Logout",
            macAddress = "",
            loginTime = loginTime,
            logoutTime = logoutTimeMillis,
            durationFormatted = durationFormatted,
            durationMillis = durationMillis,
            uploadBytes = uploadBytes,
            downloadBytes = downloadBytes,
            totalBytes = uploadBytes + downloadBytes,
            isManual = true
        )
    }
}
