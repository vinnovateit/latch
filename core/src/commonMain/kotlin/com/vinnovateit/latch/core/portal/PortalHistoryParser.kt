package com.vinnovateit.latch.core.portal

import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.mp.KSimpleDateFormat as SimpleDateFormat
import com.vinnovateit.latch.core.mp.KLocale as Locale
import com.vinnovateit.latch.core.mp.KPattern as Pattern

object PortalHistoryParser {
    private val ROW_PATTERN = Pattern.compile(
        "<tr[^>]*bgcolor=[\"']?(?:#DDDDDD|#F3F3F3)[\"']?[^>]*>(.*?)</tr>",
        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
    )
    private val COL_PATTERN = Pattern.compile(
        "<td[^>]*>(.*?)</td>",
        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
    )
    private val TAG_PATTERN = Pattern.compile("<[^>]+>")

    fun parse(html: String): List<PortalSessionRecord> {
        val records = mutableListOf<PortalSessionRecord>()
        val rowMatcher = ROW_PATTERN.matcher(html)

        while (rowMatcher.find()) {
            val rowContent = rowMatcher.group(1) ?: continue
            val colMatcher = COL_PATTERN.matcher(rowContent)
            val cols = mutableListOf<String>()

            while (colMatcher.find()) {
                val raw = colMatcher.group(1) ?: ""
                val clean = TAG_PATTERN.matcher(raw).replaceAll("").trim()
                cols.add(clean)
            }

            if (cols.size >= 8) {
                val location = cols[0]
                val mac = cols[1]
                val loginStr = cols[2]
                val logoutStr = cols[3]
                val durationStr = cols[4]
                val uploadStr = cols[5]
                val downloadStr = cols[6]
                val totalStr = cols[7]

                val loginTime = parseDate(loginStr)
                val logoutTime = parseDate(logoutStr)
                val durationMs = if (logoutTime >= loginTime && loginTime > 0) logoutTime - loginTime else 0L

                records.add(
                    PortalSessionRecord(
                        location = location,
                        macAddress = mac,
                        loginTime = loginTime,
                        logoutTime = logoutTime,
                        durationFormatted = durationStr,
                        durationMillis = durationMs,
                        uploadBytes = parseBytes(uploadStr),
                        downloadBytes = parseBytes(downloadStr),
                        totalBytes = parseBytes(totalStr)
                    )
                )
            }
        }
        return records
    }

    fun parseBytes(str: String): Long {
        val trimmed = str.trim()
        if (trimmed.isEmpty() || trimmed == "--") return 0L

        val parts = trimmed.split("\\s+".toRegex())
        if (parts.isEmpty()) return 0L

        val value = parts[0].toDoubleOrNull() ?: return 0L
        val unit = if (parts.size > 1) parts[1].uppercase() else "B"

        return when {
            unit.startsWith("GB") -> (value * 1024.0 * 1024.0 * 1024.0).toLong()
            unit.startsWith("MB") -> (value * 1024.0 * 1024.0).toLong()
            unit.startsWith("KB") -> (value * 1024.0).toLong()
            else -> value.toLong()
        }
    }

    fun parseDate(str: String): Long {
        val trimmed = str.trim()
        if (trimmed.isEmpty() || trimmed == "--") return 0L

        val formats = listOf(
            "MM/dd/yy hh:mm:ss a",
            "MM/dd/yyyy hh:mm:ss a",
            "dd-MM-yyyy HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val date = sdf.parse(trimmed)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return 0L
    }
}
