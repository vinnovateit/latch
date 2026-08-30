package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class LoginResult {
    object Success : LoginResult()
    object Failure : LoginResult()
}

/**
 * Ported from the Android app. The HTTP behaviour is intentionally byte-identical:
 * same endpoints, same POST body, same User-Agent, same 10s timeouts, same
 * redirect handling, same HTML success heuristics, same gateway-IP retry on DNS
 * failure. Only three things changed:
 *
 *   android.net.Network       -> NetworkHandle
 *   network.openConnection()  -> transport.open()
 *   android.util.Log / BuildConfig.DEBUG -> Logger / BuildInfo
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
            val loginUrl = URL(urlStr)
            logDebug("Preparing POST request to: $urlStr")

            val connection = transport.open(loginUrl, handle)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false

            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val postData = "userId=${URLEncoder.encode(userId, "UTF-8")}" +
                "&password=${URLEncoder.encode(password, "UTF-8")}" +
                "&serviceName=ProntoAuthentication"

            connection.outputStream.bufferedWriter().use { it.write(postData) }

            logDebug("Awaiting response from portal...")
            return when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    logDebug("Response body length: ${response.length} chars")
                    val responseLower = response.lowercase()
                    val isSuccess = "access granted" in responseLower ||
                        "you have successfully connected" in responseLower ||
                        "already logged in" in responseLower ||
                        "http-equiv=\"refresh\"" in responseLower ||
                        "http://example.com" in responseLower
                    logDebug("Login success evaluation string match: $isSuccess")
                    if (!isSuccess) {
                        // Only the length was logged before, which can't distinguish
                        // "real login failure" from a Pronto page served mid session-
                        // teardown right after a fresh logout -- log unconditionally
                        // (not logDebug) since this is the one signal that can confirm
                        // that theory from a future capture instead of guessing again.
                        logWarning("Login response didn't match any known success string. Body: ${response.take(500)}")
                    }

                    if (isSuccess) LoginResult.Success else LoginResult.Failure
                }

                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP -> {
                    logWarning("Login resulted in a redirect ($responseCode). Treating as success.")
                    LoginResult.Success
                }

                else -> {
                    logWarning("Login failed with unexpected response code: $responseCode")
                    LoginResult.Failure
                }
            }
        }

        return try {
            doAttempt(targetUrlStr)
        } catch (e: java.net.UnknownHostException) {
            logError("Login failed with UnknownHostException.", e)
            if (fallbackIp != null) {
                val fallbackTargetUrlStr = targetUrlStr.replace(PORTAL_HOST, fallbackIp)
                logDebug("Retrying login with fallback IP: $fallbackTargetUrlStr")
                try {
                    doAttempt(fallbackTargetUrlStr)
                } catch (fallbackE: Exception) {
                    logError("Fallback login failed: ${fallbackE.message}", fallbackE)
                    LoginResult.Failure
                }
            } else {
                LoginResult.Failure
            }
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
    }

    fun attemptLogout(
        handle: NetworkHandle? = null,
        useAlternate: Boolean = false,
        fallbackIp: String? = null,
    ): Boolean {
        logDebug("Initiating logout attempt (useAlternate=$useAlternate)")
        val targetUrlStr = if (useAlternate) SECURE_LOGOUT_URL else LOGOUT_URL

        fun doAttempt(urlStr: String): Boolean {
            val url = URL(urlStr)
            val connection = transport.open(url, handle)
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            connection.connect()

            val code = connection.responseCode
            logDebug("Logout returned response code: $code")

            // Drain the stream so the connection can be reused/closed cleanly.
            try {
                (if (code >= 400) connection.errorStream else connection.inputStream)
                    ?.buffered()?.use { it.readBytes() }
            } catch (e: Exception) {
                logDebug("Stream drain exception (ignored): ${e.message}")
            }

            connection.disconnect()
            return code in 200..399
        }

        return try {
            doAttempt(targetUrlStr)
        } catch (e: java.net.UnknownHostException) {
            logError("Logout failed with UnknownHostException.", e)
            if (fallbackIp != null) {
                val fallbackTargetUrlStr = targetUrlStr.replace(PORTAL_HOST, fallbackIp)
                try {
                    doAttempt(fallbackTargetUrlStr)
                } catch (fallbackE: Exception) {
                    logError("Fallback logout failed: ${fallbackE.message}", fallbackE)
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            logError("Logout failed with exception: ${e.message}", e)
            false
        }
    }
}
