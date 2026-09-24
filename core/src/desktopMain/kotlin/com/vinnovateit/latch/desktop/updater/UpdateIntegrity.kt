package com.vinnovateit.latch.desktop.updater

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// HttpURLConnection defaults to *no* timeout. On a captive-portal network a
// half-open socket would otherwise park the UI on "Downloading... 0%" forever,
// with no way out but killing the app.
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000

private const val USER_AGENT = "Latch-Updater"

private val SHA256_DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

/**
 * A release asset whose identity is pinned by the GitHub releases API: the
 * bytes fetched from [url] are only ever accepted if there are exactly [size]
 * of them and they hash to [sha256].
 */
internal data class TrustedAsset(
    val name: String,
    val url: String,
    val size: Long,
    /** Lowercase hex. */
    val sha256: String,
)

/**
 * Parses the `digest` GitHub publishes on each release asset (`sha256:<hex>`).
 *
 * Anything else -- missing, another algorithm, the wrong length -- is null,
 * and the caller treats null as "cannot verify", never as "skip verifying".
 */
internal fun parseSha256Digest(digest: String?): String? =
    digest?.trim()?.let { SHA256_DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().toHex()
}

/** Exact, case-insensitive comparison of two hex digests. */
internal fun digestsEqual(expectedHex: String, actualHex: String): Boolean =
    expectedHex.length == actualHex.length &&
        MessageDigest.isEqual(expectedHex.lowercase().toByteArray(), actualHex.lowercase().toByteArray())

/**
 * The downloaded bytes are not the release asset they claim to be. Distinct
 * from a plain [IOException] (network trouble) so the two surface differently.
 */
internal class UpdateIntegrityException(message: String) : IOException(message)

internal class UpdateHttpResponse(
    val code: Int,
    /** -1 when the server sent none. */
    val contentLength: Long,
    val contentType: String?,
    private val openBody: () -> InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    val body: InputStream by lazy(openBody)
    override fun close() = onClose()
}

/** The updater's only network dependency, so tests can script any response. */
internal fun interface UpdateHttp {
    fun get(url: String, accept: String?): UpdateHttpResponse
}

internal object UrlConnectionUpdateHttp : UpdateHttp {
    override fun get(url: String, accept: String?): UpdateHttpResponse {
        // Redirects are followed (github.com hands the asset off to its CDN),
        // but HttpURLConnection never follows one from https to http, so a
        // downgrade surfaces as a non-200 code instead of a body.
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            if (accept != null) setRequestProperty("Accept", accept)
        }
        val code = conn.responseCode
        return UpdateHttpResponse(
            code = code,
            contentLength = conn.contentLengthLong,
            contentType = conn.contentType,
            openBody = { if (code in 200..299) conn.inputStream else conn.errorStream ?: InputStream.nullInputStream() },
            onClose = { conn.disconnect() },
        )
    }
}
