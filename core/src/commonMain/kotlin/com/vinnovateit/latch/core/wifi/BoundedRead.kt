package com.vinnovateit.latch.core.wifi

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Far more than a login or logout page; a portal has no reason to send more. */
internal const val MAX_PORTAL_PAGE_BYTES = 256 * 1024

/** A session-history page grows with the account, so it gets more room. */
internal const val MAX_HISTORY_PAGE_BYTES = 4 * 1024 * 1024

/**
 * Reads at most [limit] bytes and leaves the rest unread, so a response of any
 * size costs a bounded amount of memory.
 *
 * InputStream.readNBytes is Java 11 API, which Android only has from API 33.
 */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    while (output.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun InputStream.readTextUpTo(limit: Int): String = String(readUpTo(limit), Charsets.UTF_8)
