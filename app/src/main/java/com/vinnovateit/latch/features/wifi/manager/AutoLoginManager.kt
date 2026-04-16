package com.vinnovateit.latch.features.wifi.manager

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.vinnovateit.latch.common.debug.DebugRuntimeLogger
import com.vinnovateit.latch.common.network.NetworkAwareDnsResolver
import java.io.IOException
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URL
import java.net.URLEncoder

sealed class LoginResult {
    object Success : LoginResult()
    object Failure : LoginResult()
    object UnsupportedNetwork : LoginResult()
}
object AutoLoginManager {

    private const val LOGIN_URL =
        "http://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com"
    private const val LOGOUT_URL = "http://phc.prontonetworks.com/cgi-bin/authlogout"
    private const val TARGET_PORTAL_HOST = "phc.prontonetworks.com"
    private const val TARGET_PORTAL_BASE_DOMAIN = "prontonetworks.com"

    private fun toIpHostLiteral(ip: String): String {
        return if (':' in ip && !ip.startsWith("[")) "[$ip]" else ip
    }

    private fun openResolvedConnection(
        url: URL,
        network: Network?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestMethod: String,
        hostHeader: String? = null
    ): HttpURLConnection {
        // Use network.openConnection() directly — this routes through the network's own
        // DNS stack (the local gateway), bypassing Private DNS/DoT entirely.
        // No pre-resolution needed; the captive portal gateway handles DNS itself.
        val connection = try {
            (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        } catch (e: IOException) {
            if (network != null) {
                Log.w("AutoLoginManager", "Network-bound connection failed, falling back to direct URL connection: ${e.message}")
                url.openConnection() as HttpURLConnection
            } else {
                throw e
            }
        }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = requestMethod
        if (!hostHeader.isNullOrBlank()) {
            connection.setRequestProperty("Host", hostHeader)
        }
        return connection
    }

    private fun resolveLoginUrl(network: Network?, host: String, file: String): URL {
        val resolvedAddress = NetworkAwareDnsResolver.resolveFirst(host, network)
        val hostLiteral = toIpHostLiteral(resolvedAddress.hostAddress ?: throw UnknownHostException("No address for $host"))
        return URL("http://$hostLiteral$file")
    }

    private fun createResolvedLoginConnection(network: Network?): HttpURLConnection {
        val primary = URL(LOGIN_URL)
        // #region agent log
        DebugRuntimeLogger.log(
            runId = "pre-fix",
            hypothesisId = "G",
            location = "AutoLoginManager.kt:createResolvedLoginConnection",
            message = "createResolvedLoginConnection entry",
            data = mapOf(
                "hasNetwork" to (network != null),
                "network" to (network?.toString() ?: "null"),
                "primaryHost" to primary.host
            )
        )
        // #endregion
        return try {
            val resolvedUrl = resolveLoginUrl(network, primary.host, primary.file)
            openResolvedConnection(
                url = resolvedUrl,
                network = network,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000,
                requestMethod = "POST",
                hostHeader = primary.host
            )
        } catch (e: UnknownHostException) {
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "E",
                location = "AutoLoginManager.kt:createResolvedLoginConnection",
                message = "Primary login host DNS failed; trying base domain",
                data = mapOf(
                    "primaryHost" to primary.host,
                    "fallbackHost" to TARGET_PORTAL_BASE_DOMAIN,
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                )
            )
            // #endregion
            val fallbackResolvedUrl = resolveLoginUrl(network, TARGET_PORTAL_BASE_DOMAIN, primary.file)
            openResolvedConnection(
                url = fallbackResolvedUrl,
                network = network,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000,
                requestMethod = "POST",
                hostHeader = TARGET_PORTAL_BASE_DOMAIN
            )
        }
    }

    private fun intToIpv4(value: Int): String {
        return "${value and 0xff}.${(value shr 8) and 0xff}.${(value shr 16) and 0xff}.${(value shr 24) and 0xff}"
    }

    private fun createLoginConnectionWithGatewayFallback(context: Context, network: Network?): HttpURLConnection {
        return try {
            createResolvedLoginConnection(network)
        } catch (e: UnknownHostException) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val gatewayInt = wifiManager?.dhcpInfo?.gateway ?: 0
            if (gatewayInt == 0) throw e

            val gatewayIp = intToIpv4(gatewayInt)
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "E",
                location = "AutoLoginManager.kt:createLoginConnectionWithGatewayFallback",
                message = "DNS fallback failed; trying DHCP gateway login URL",
                data = mapOf(
                    "gatewayIp" to gatewayIp,
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                )
            )
            // #endregion

            val gatewayUrl = URL("http://$gatewayIp/cgi-bin/authlogin?URI=http://example.com")
            val connection =
                (network?.openConnection(gatewayUrl) ?: gatewayUrl.openConnection()) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Host", TARGET_PORTAL_HOST)
            connection
        }
    }

    fun isTargetCaptivePortal(network: Network?): Boolean {
        return try {
            val url = URL(LOGIN_URL)
            val resolvedUrl = resolveLoginUrl(network, url.host, url.file)
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "B",
                location = "AutoLoginManager.kt:isTargetCaptivePortal",
                message = "Starting target portal check",
                data = mapOf(
                    "hasNetwork" to (network != null),
                    "url" to LOGIN_URL
                )
            )
            // #endregion
            val connection = openResolvedConnection(
                url = resolvedUrl,
                network = network,
                connectTimeoutMs = 3000,
                readTimeoutMs = 3000,
                requestMethod = "GET",
                hostHeader = url.host
            )
            connection.connect()
            val responseCode = connection.responseCode
            val locationHeader = connection.getHeaderField("Location")
            connection.disconnect()
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "B",
                location = "AutoLoginManager.kt:isTargetCaptivePortal",
                message = "Target portal check completed",
                data = mapOf(
                    "responseCode" to responseCode,
                    "hasLocationHeader" to (locationHeader != null)
                )
            )
            // #endregion
            val isRedirect = responseCode in listOf(
                HttpURLConnection.HTTP_MOVED_PERM, // 301
                HttpURLConnection.HTTP_MOVED_TEMP, // 302
                HttpURLConnection.HTTP_SEE_OTHER,  // 303
                307,
                308
            )
            val redirectHost = if (!locationHeader.isNullOrBlank()) {
                runCatching { URL(locationHeader).host }.getOrNull()
            } else null
            val redirectHostResolvable = if (!redirectHost.isNullOrBlank()) {
                runCatching { NetworkAwareDnsResolver.resolveFirst(redirectHost, network) }.isSuccess
            } else false
            val redirectLooksLikePortal = if (isRedirect && !locationHeader.isNullOrBlank()) {
                val hostMatches = !redirectHost.isNullOrBlank() && (
                    redirectHost.equals(TARGET_PORTAL_HOST, ignoreCase = true) ||
                        redirectHost.equals(TARGET_PORTAL_BASE_DOMAIN, ignoreCase = true) ||
                        redirectHost.endsWith(".$TARGET_PORTAL_BASE_DOMAIN", ignoreCase = true)
                    )
                hostMatches ||
                    locationHeader.contains(TARGET_PORTAL_HOST, ignoreCase = true) ||
                    locationHeader.contains(TARGET_PORTAL_BASE_DOMAIN, ignoreCase = true)
            } else false

            val isTarget = responseCode == HttpURLConnection.HTTP_OK || (redirectLooksLikePortal && redirectHostResolvable)
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "B",
                location = "AutoLoginManager.kt:isTargetCaptivePortal",
                message = "Target portal final decision",
                data = mapOf(
                    "responseCode" to responseCode,
                    "isRedirect" to isRedirect,
                    "redirectHost" to (redirectHost ?: "null"),
                    "locationHeader" to (locationHeader ?: "null"),
                    "redirectHostResolvable" to redirectHostResolvable,
                    "redirectLooksLikePortal" to redirectLooksLikePortal,
                    "isTarget" to isTarget
                )
            )
            // #endregion
            isTarget
        } catch (e: UnknownHostException) {
            Log.w("AutoLoginManager", "Portal DNS blocked; treating as target", e)
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "B",
                location = "AutoLoginManager.kt:isTargetCaptivePortal",
                message = "Target check DNS blocked; forcing captive handling",
                data = mapOf(
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null"),
                    "isTarget" to true
                )
            )
            // #endregion
            true
        } catch (e: Exception) {
            Log.d("AutoLoginManager", "Target portal check failed: ${e.message}")
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "B",
                location = "AutoLoginManager.kt:isTargetCaptivePortal",
                message = "Target portal check exception",
                data = mapOf(
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                )
            )
            // #endregion
            false
        }
    }

    fun attemptLogin(context: Context, userId: String, password: String, network: Network? = null): LoginResult {
        return try {
            val connection = createLoginConnectionWithGatewayFallback(context, network)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

            val postData = "userId=${URLEncoder.encode(userId, "UTF-8")}" +
              "&password=${URLEncoder.encode(password, "UTF-8")}" +
              "&serviceName=ProntoAuthentication"

            connection.outputStream.bufferedWriter().use { it.write(postData) }

            when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> { // 200
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val isSuccess = "Access Granted" in response || "You have successfully connected" in response || "already logged in" in response.lowercase()
                    val responsePreview = response
                        .replace(Regex("\\s+"), " ")
                        .take(500)
                    val looksLikeLoginPage = response.contains("name=\"userId\"", ignoreCase = true) ||
                        response.contains("name='userId'", ignoreCase = true) ||
                        response.contains("password", ignoreCase = true)
                    // #region agent log
                    DebugRuntimeLogger.log(
                        runId = "pre-fix",
                        hypothesisId = "E",
                        location = "AutoLoginManager.kt:attemptLogin",
                        message = "Login HTTP 200 parsed",
                        data = mapOf(
                            "isSuccess" to isSuccess,
                            "looksLikeLoginPage" to looksLikeLoginPage,
                            "responsePreview" to responsePreview
                        )
                    )
                    // #endregion
                    if (isSuccess) LoginResult.Success else LoginResult.Failure
                }
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP -> { // 301, 302
                    Log.d("AutoLoginManager", "Login resulted in a redirect ($responseCode). Assuming unsupported network.")
                    // #region agent log
                    DebugRuntimeLogger.log(
                        runId = "pre-fix",
                        hypothesisId = "E",
                        location = "AutoLoginManager.kt:attemptLogin",
                        message = "Login redirect treated as unsupported",
                        data = mapOf("responseCode" to responseCode)
                    )
                    // #endregion
                    LoginResult.UnsupportedNetwork
                }
                else -> {
                    Log.w("AutoLoginManager", "Login failed with unexpected response code: $responseCode")
                    // #region agent log
                    DebugRuntimeLogger.log(
                        runId = "pre-fix",
                        hypothesisId = "E",
                        location = "AutoLoginManager.kt:attemptLogin",
                        message = "Login unexpected response code",
                        data = mapOf("responseCode" to responseCode)
                    )
                    // #endregion
                    LoginResult.Failure
                }
            }
        } catch (e: Exception) {
            Log.e("AutoLoginManager", "Login failed with exception", e)
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "E",
                location = "AutoLoginManager.kt:attemptLogin",
                message = "Login exception",
                data = mapOf(
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                )
            )
            // #endregion
            LoginResult.Failure
        }
    }

    fun attemptLogout(network: Network? = null): Boolean {
        return try {
            val url = URL(LOGOUT_URL)
            val resolvedUrl = resolveLoginUrl(network, url.host, url.file)
            // Use openResolvedConnection to ensure NetworkAwareDnsResolver and network.openConnection are applied
            val connection = openResolvedConnection(
                url = resolvedUrl,
                network = network,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000,
                requestMethod = "GET",
                hostHeader = url.host
            )
            // connection.instanceFollowRedirects, connectTimeout, readTimeout, requestMethod are handled by openResolvedConnection 
            connection.instanceFollowRedirects = false     // Don't auto-follow; we just care that it responded
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            val code = connection.responseCode
            Log.d("AutoLoginManager", "Logout response code: $code")

            // Drain response to avoid leaked connections
            try {
                (if (code >= 400) connection.errorStream else connection.inputStream)
                    ?.buffered()?.use { it.readBytes() }
            } catch (_: Exception) { /* ignore */ }

            connection.disconnect()
            code in 200..399
        } catch (e: Exception) {
            Log.e("AutoLoginManager", "Logout failed: ${e.message}")
            false
        }
    }

}