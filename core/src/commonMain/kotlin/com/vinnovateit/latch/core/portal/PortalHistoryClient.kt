package com.vinnovateit.latch.core.portal

import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.platform.logger
import com.vinnovateit.latch.core.wifi.MAX_HISTORY_PAGE_BYTES
import com.vinnovateit.latch.core.wifi.MAX_PORTAL_PAGE_BYTES
import com.vinnovateit.latch.core.wifi.readTextUpTo
import com.vinnovateit.latch.core.wifi.readUpTo
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

class PortalHistoryClient(
    private val transport: HttpTransport,
    private val logger: Logger = Platform.logger,
) {
    companion object {
        private const val TAG = "PortalHistoryClient"
        const val DEFAULT_PORTAL_HOST = "136.233.9.110"
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 8000
        private const val MAX_REDIRECTS = 3
    }

    internal data class DateFilter(
        val startYear: String,
        val startMonth: String,
        val startDay: String,
        val endYear: String,
        val endMonth: String,
        val endDay: String,
    )

    internal fun computeDateFilter(userId: String): DateFilter {
        val regYearMatch = Regex("^(\\d{2})").find(userId.trim())
        val startYear = if (regYearMatch != null) {
            val yr = regYearMatch.groupValues[1].toIntOrNull() ?: 24
            val yearNum = 2000 + yr
            if (yearNum < 2024) "2024" else yearNum.toString()
        } else {
            "2024" // Oldest year in filter dropdown
        }
        val startMonth = "00" // Jan (0-indexed)
        val startDay = "01"

        val cal = java.util.Calendar.getInstance()
        val endYear = cal.get(java.util.Calendar.YEAR).toString()
        val endMonth = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.MONTH))
        val endDay = String.format(java.util.Locale.US, "%02d", cal.get(java.util.Calendar.DAY_OF_MONTH))

        return DateFilter(
            startYear = startYear,
            startMonth = startMonth,
            startDay = startDay,
            endYear = endYear,
            endMonth = endMonth,
            endDay = endDay,
        )
    }

    fun fetchHistory(
        userId: String,
        password: String,
        handle: NetworkHandle? = null,
        host: String = DEFAULT_PORTAL_HOST
    ): Result<List<PortalSessionRecord>> {
        return try {
            val mainUrl = URL("http://$host/registration/Main.jsp?wispId=1")
            logger.d(TAG, "Requesting portal history URL: $mainUrl")
            val conn1 = transport.open(mainUrl, handle)
            conn1.connectTimeout = CONNECT_TIMEOUT_MS
            conn1.readTimeout = READ_TIMEOUT_MS
            conn1.instanceFollowRedirects = false
            conn1.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

            val responseCode1 = conn1.responseCode
            logger.d(TAG, "Response code: $responseCode1")
            if (responseCode1 != 200) {
                return Result.failure(IOException("The portal did not serve its login page (HTTP $responseCode1)"))
            }
            val mainHtml = conn1.inputStream.use { it.readTextUpTo(MAX_PORTAL_PAGE_BYTES) }
            val setCookieHeader = conn1.getHeaderField("Set-Cookie")
            var cookie = setCookieHeader?.substringBefore(";") ?: ""

            // Only the portal's own login form gets the credentials. Any other
            // page reachable at this address -- another network's router, say --
            // has no such form, or one that posts somewhere else.
            val loginUrl = loginFormAction(mainHtml, mainUrl)
                ?: return Result.failure(IOException("The page at the portal address is not the portal's login form"))

            // Step 2: POST credentials
            val postData = buildString {
                append("loginUserId=").append(URLEncoder.encode(userId, "UTF-8"))
                append("&authType=").append(URLEncoder.encode("Pronto", "UTF-8"))
                append("&loginPassword=").append(URLEncoder.encode(password, "UTF-8"))
                append("&submit=").append(URLEncoder.encode("Login", "UTF-8"))
            }

            val conn2 = transport.open(loginUrl, handle)
            conn2.requestMethod = "POST"
            conn2.doOutput = true
            conn2.instanceFollowRedirects = false
            conn2.connectTimeout = CONNECT_TIMEOUT_MS
            conn2.readTimeout = READ_TIMEOUT_MS
            conn2.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn2.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            if (cookie.isNotEmpty()) {
                conn2.setRequestProperty("Cookie", cookie)
            }

            conn2.outputStream.bufferedWriter().use { it.write(postData) }
            val conn2Cookie = conn2.getHeaderField("Set-Cookie")
            if (!conn2Cookie.isNullOrEmpty()) {
                cookie = conn2Cookie.substringBefore(";")
            }
            val responseCode2 = conn2.responseCode
            val stream2 = if (responseCode2 in 200..399) conn2.inputStream else conn2.errorStream
            stream2?.use { it.readUpTo(MAX_PORTAL_PAGE_BYTES) }

            // Step 3: Query history with registration number date range
            val dateFilter = computeDateFilter(userId)
            val filterPostData = buildString {
                append("location=").append(URLEncoder.encode("allLocations", "UTF-8"))
                append("&parameter=").append(URLEncoder.encode("custom", "UTF-8"))
                append("&customStartMonth=").append(URLEncoder.encode(dateFilter.startMonth, "UTF-8"))
                append("&customStartDay=").append(URLEncoder.encode(dateFilter.startDay, "UTF-8"))
                append("&customStartYear=").append(URLEncoder.encode(dateFilter.startYear, "UTF-8"))
                append("&customEndMonth=").append(URLEncoder.encode(dateFilter.endMonth, "UTF-8"))
                append("&customEndDay=").append(URLEncoder.encode(dateFilter.endDay, "UTF-8"))
                append("&customEndYear=").append(URLEncoder.encode(dateFilter.endYear, "UTF-8"))
                append("&button=").append(URLEncoder.encode("View", "UTF-8"))
            }

            val filterUrl = URL("http://$host/registration/customerSessionHistory.do")
            logger.d(TAG, "Requesting portal history URL: $filterUrl")
            var historyHtml = readPortalPage(filterUrl, handle, cookie, filterPostData)
            var records = PortalHistoryParser.parse(historyHtml)
            logger.d(TAG, "Parsed ${records.size} records")

            // Fallback to default GET if filtered request returns no records
            if (records.isEmpty()) {
                val fallbackUrl = URL("http://$host/registration/main.do?content_key=%2FCustomerSessionHistory.jsp")
                logger.d(TAG, "Requesting portal history URL: $fallbackUrl")
                historyHtml = readPortalPage(fallbackUrl, handle, cookie, postBody = null)
                records = PortalHistoryParser.parse(historyHtml)
                logger.d(TAG, "Parsed ${records.size} records")
            }

            Result.success(records)
        } catch (e: Exception) {
            logger.e(TAG, "Error fetching portal history: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Reads a logged-in page, following redirects by hand so that one leaving
     * the portal is refused instead of being sent the session cookie.
     */
    private fun readPortalPage(
        start: URL,
        handle: NetworkHandle?,
        cookie: String,
        postBody: String?,
    ): String {
        var url = start
        var body = postBody
        repeat(MAX_REDIRECTS + 1) {
            val connection = transport.open(url, handle)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
            val sending = body
            if (sending != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.bufferedWriter().use { it.write(sending) }
            } else {
                connection.requestMethod = "GET"
            }

            val code = connection.responseCode
            logger.d(TAG, "Response code: $code")
            if (code !in 300..399) return connection.inputStream.use { it.readTextUpTo(MAX_HISTORY_PAGE_BYTES) }

            val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without a Location")
            val next = URL(url, location)
            if (!isSameOrigin(next, url)) throw IOException("The portal redirected away from its own address")
            url = next
            body = null
        }
        throw IOException("Too many redirects from the portal")
    }
}

/** Where the login form at [page] posts to, or null if it has none or it posts off the portal. */
internal fun loginFormAction(html: String, page: URL): URL? {
    // The action may carry ;jsessionid=
    val matcher = Pattern.compile("action=[\"']([^\"']*chooseAuth\\.do[^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
    if (!matcher.find()) return null
    val action = runCatching { URL(page, matcher.group(1)) }.getOrNull() ?: return null
    return action.takeIf { isSameOrigin(it, page) }
}

private fun isSameOrigin(a: URL, b: URL): Boolean =
    a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
