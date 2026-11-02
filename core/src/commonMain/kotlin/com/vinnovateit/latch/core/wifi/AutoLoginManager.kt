package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface LoginResult {
    data object Success : LoginResult

    /**
     * The portal answered and refused the credentials. Sending them again
     * gets the same answer and counts towards the portal's lockout.
     */
    data object Rejected : LoginResult

    /** The request failed or the portal gave an answer Latch does not recognise. */
    data object Failure : LoginResult
}

/**
 * Ported from the Android app. The requests are intentionally byte-identical:
 * same endpoints, same POST body, same User-Agent, same timeouts, same
 * redirect handling, same HTML success heuristics. The port swaps
 *
 *   android.net.Network       -> NetworkHandle
 *   network.openConnection()  -> transport.open()
 *   android.util.Log / BuildConfig.DEBUG -> Logger / BuildInfo
 *
 * A request that fails gets one retry through the gateway IP. That covers a
 * portal host that does not resolve (a custom DNS or VPN resolver before
 * login), cannot be reached (no route off the subnet before login) or gives
 * an answer Latch does not recognise. A refusal is never retried.
 *
 * Do not "improve" the success detection. Matching on response HTML is ugly but
 * it is the only signal Pronto's portal gives us.
 */
class AutoLoginManager(
    private val transport: HttpTransport,
    private val logger: Logger,
    private val buildInfo: BuildInfo,
) {
    private companion object {
        const val LOGIN_URL =
            "http://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com"
        const val LOGOUT_URL = "http://phc.prontonetworks.com/cgi-bin/authlogout"

        const val SECURE_LOGIN_URL =
            "https://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com"
        const val SECURE_LOGOUT_URL = "https://phc.prontonetworks.com/cgi-bin/authlogout"

        const val PORTAL_HOST = "phc.prontonetworks.com"
        const val TAG = "AutoLoginManager"
    }

    private fun logDebug(message: String) {
        if (buildInfo.isDebug) logger.d(TAG, message)
    }

    // Unlike Android, warnings and errors are logged unconditionally: a tray app
    // that starts hidden at login has no console, so the log file is the only
    // support channel there is.
    private fun logWarning(message: String) = logger.w(TAG, message)
    private fun logError(message: String, throwable: Throwable? = null) =
        logger.e(TAG, message, throwable)

    fun attemptLogin(
        userId: String,
        password: String,
        handle: NetworkHandle? = null,
        useAlternate: Boolean = false,
        fallbackIp: String? = null,
    ): LoginResult {
        logDebug("Initiating login attempt for user: $userId (useAlternate=$useAlternate)")
        val targetUrlStr = if (useAlternate) SECURE_LOGIN_URL else LOGIN_URL

        fun doAttempt(urlStr: String): LoginResult {
            val start = System.currentTimeMillis()
            val loginUrl = URL(urlStr)
            logDebug("Preparing POST request to: $urlStr (user=$userId)")

            val connection = transport.open(loginUrl, handle)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false

            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val postData = "userId=${URLEncoder.encode(userId, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}" +
                "&serviceName=ProntoAuthentication"

            connection.outputStream.bufferedWriter().use { it.write(postData) }

            logDebug("Awaiting response from portal at $urlStr...")
            val responseCode = connection.responseCode
            val elapsed = System.currentTimeMillis() - start
            return when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    logDebug("Portal responded in ${elapsed}ms: HTTP 200 (Body: ${response.length} chars)")
                    val responseLower = response.lowercase()
                    val hasExplicitError = "invalid" in responseLower ||
                        "incorrect" in responseLower ||
                        "wrong password" in responseLower ||
                        "authentication failed" in responseLower ||
                        "user not found" in responseLower ||
                        "account expired" in responseLower ||
                        "session limit" in responseLower ||
                        "max session" in responseLower ||
                        "login failed" in responseLower

                    val hasSuccessToken = "access granted" in responseLower ||
                        "successfully" in responseLower ||
                        "logged in" in responseLower ||
                        "already logged in" in responseLower ||
                        "welcome" in responseLower ||
                        "success" in responseLower ||
                        "http-equiv=\"refresh\"" in responseLower ||
                        "location.href" in responseLower ||
                        "window.location" in responseLower ||
                        "http://example.com" in responseLower ||
                        !hasExplicitError

                    val isSuccess = !hasExplicitError && hasSuccessToken
                    logDebug("Login evaluation: isSuccess=$isSuccess (hasExplicitError=$hasExplicitError)")
                    if (!isSuccess) {
                        logWarning("Login response indicated failure in ${elapsed}ms. Snippet: ${response.take(300).replace("\n", " ")}")
                    }

                    if (isSuccess) LoginResult.Success else LoginResult.Rejected
                }

                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP -> {
                    val location = connection.getHeaderField("Location")
                    logWarning("Login resulted in a redirect in ${elapsed}ms (HTTP $responseCode -> $location). Treating as success.")
                    LoginResult.Success
                }

                else -> {
                    logWarning("Login failed in ${elapsed}ms with unexpected HTTP code: $responseCode")
                    LoginResult.Failure
                }
            }
        }

        fun attemptOrFailure(urlStr: String): LoginResult = try {
            doAttempt(urlStr)
        } catch (e: java.net.UnknownHostException) {
            logError("Login failed with UnknownHostException.", e)
            LoginResult.Failure
        } catch (e: javax.net.ssl.SSLException) {
            // Some portal deployments serve HTTPS off a cert chain signed with a
            // legacy/weak-hash CA that the platform TLS stack now hard-rejects
            // unconditionally -- this is a permanent portal misconfiguration, not
            // a transient app-side error, so it doesn't deserve a full stack
            // trace at ERROR on every login attempt.
            logWarning("HTTPS login attempt rejected at the TLS layer (portal certificate issue): ${e.message}")
            LoginResult.Failure
        } catch (e: Exception) {
            logError("Login failed with exception: ${e.message}", e)
            LoginResult.Failure
        }

        val result = attemptOrFailure(targetUrlStr)
        if (result != LoginResult.Failure || fallbackIp == null) return result
        val fallbackTargetUrlStr = targetUrlStr.replace(PORTAL_HOST, fallbackIp)
        logDebug("Retrying login with fallback IP: $fallbackTargetUrlStr")
        return attemptOrFailure(fallbackTargetUrlStr)
    }

    data class LogoutResult(
        val success: Boolean,
        val responseHtml: String? = null,
    )

    fun attemptLogout(
        handle: NetworkHandle? = null,
        useAlternate: Boolean = false,
        fallbackIp: String? = null,
    ): Boolean = attemptLogoutWithResponse(handle, useAlternate, fallbackIp).success

    fun attemptLogoutWithResponse(
        handle: NetworkHandle? = null,
        useAlternate: Boolean = false,
        fallbackIp: String? = null,
    ): LogoutResult {
        logDebug("Initiating logout attempt (useAlternate=$useAlternate)")
        val targetUrlStr = if (useAlternate) SECURE_LOGOUT_URL else LOGOUT_URL

        fun doAttempt(urlStr: String): LogoutResult {
            val url = URL(urlStr)
            val connection = transport.open(url, handle)
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            return try {
                connection.connect()

                val code = connection.responseCode
                logDebug("Logout returned response code: $code")

                // Read the response body so we can extract logout session metrics
                val body = try {
                    (if (code >= 400) connection.errorStream else connection.inputStream)
                        ?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    logDebug("Stream read exception (ignored): ${e.message}")
                    null
                }
                LogoutResult(success = code in 200..399, responseHtml = body)
            } finally {
                try {
                    connection.disconnect()
                } catch (_: Throwable) {}
            }
        }

        return try {
            doAttempt(targetUrlStr)
        } catch (e: java.io.IOException) {
            // The same unreachable portal host the login falls back on.
            logError("Logout failed with ${e::class.simpleName}.", e)
            if (fallbackIp != null) {
                val fallbackTargetUrlStr = targetUrlStr.replace(PORTAL_HOST, fallbackIp)
                try {
                    doAttempt(fallbackTargetUrlStr)
                } catch (fallbackE: Exception) {
                    logError("Fallback logout failed: ${fallbackE.message}", fallbackE)
                    LogoutResult(false)
                }
            } else {
                LogoutResult(false)
            }
        } catch (e: Exception) {
            logError("Logout failed with exception: ${e.message}", e)
            LogoutResult(false)
        }
    }
}
