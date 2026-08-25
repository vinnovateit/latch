package com.vinnovateit.latch.features.wifi.manager

import android.net.Network
import android.util.Log
import com.vinnovateit.latch.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class LoginResult {
    object Success : LoginResult()
    object Failure : LoginResult()
}

object AutoLoginManager {

    private const val LOGIN_URL = "http://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com"
    private const val LOGOUT_URL = "http://phc.prontonetworks.com/cgi-bin/authlogout"

    private const val SECURE_LOGIN_URL = "https://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com"
    private const val SECURE_LOGOUT_URL = "https://phc.prontonetworks.com/cgi-bin/authlogout"

    private const val TAG = "AutoLoginManager"

    // Helper functions for conditional logging
    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    }

    private fun logWarning(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }



    fun attemptLogin(userId: String, password: String, network: Network? = null, useAlternate: Boolean = false, fallbackIp: String? = null): LoginResult {
        logDebug("Initiating login attempt for user: $userId (useAlternate=$useAlternate)")
        val targetUrlStr = if (useAlternate) SECURE_LOGIN_URL else LOGIN_URL
        
        fun doAttempt(urlStr: String): LoginResult {
            val loginUrl = URL(urlStr)
            logDebug("Preparing POST request to: $urlStr")

            val connection = (network?.openConnection(loginUrl) ?: loginUrl.openConnection()) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false

            logDebug("Setting connection headers and timeouts...")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            logDebug("Encoding credentials and building POST payload...")
            val postData = "userId=${URLEncoder.encode(userId, "UTF-8")}" +
                    "&password=${URLEncoder.encode(password, "UTF-8")}" +
                    "&serviceName=ProntoAuthentication"

            logDebug("Writing POST payload to output stream...")
            connection.outputStream.bufferedWriter().use { it.write(postData) }

            logDebug("Awaiting response from portal...")
            return when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    logDebug("Received 200 OK. Reading response body...")
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    logDebug("Response body length: ${response.length} chars")
                    val responseLower = response.lowercase()
                    val isSuccess = "access granted" in responseLower ||
                            "you have successfully connected" in responseLower ||
                            "already logged in" in responseLower ||
                            "http-equiv=\"refresh\"" in responseLower ||
                            "http://example.com" in responseLower
                    logDebug("Login success evaluation string match: $isSuccess")

                    if (isSuccess) LoginResult.Success else LoginResult.Failure
                }
                HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP -> {
                    logWarning("Login resulted in a redirect ($responseCode). Redirects often mean successful login.")
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
                val fallbackTargetUrlStr = targetUrlStr.replace("phc.prontonetworks.com", fallbackIp)
                logDebug("Retrying login with fallback IP: $fallbackTargetUrlStr")
                try {
                    doAttempt(fallbackTargetUrlStr)
                } catch (fallbackE: Exception) {
                    logError("Fallback login failed with exception: ${fallbackE.message}", fallbackE)
                    LoginResult.Failure
                }
            } else {
                LoginResult.Failure
            }
        } catch (e: Exception) {
            logError("Login failed with exception: ${e.message}", e)
            LoginResult.Failure
        }
    }

    fun attemptLogout(useAlternate: Boolean = false, fallbackIp: String? = null, network: Network? = null): Boolean {
        logDebug("Initiating logout attempt (useAlternate=$useAlternate)")
        val targetUrlStr = if (useAlternate) SECURE_LOGOUT_URL else LOGOUT_URL

        fun doAttempt(urlStr: String): Boolean {
            val url = URL(urlStr)
            logDebug("Opening connection to: $urlStr")
            val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            logDebug("Connecting to logout endpoint...")
            connection.connect()

            val code = connection.responseCode
            logDebug("Logout returned response code: $code")

            logDebug("Draining response streams to prevent connection leaks...")
            try {
                (if (code >= 400) connection.errorStream else connection.inputStream)
                    ?.buffered()?.use { it.readBytes() }
            } catch (e: Exception) {
                logDebug("Stream drain exception (ignored): ${e.message}")
            }

            connection.disconnect()
            val success = code in 200..399
            logDebug("Logout success evaluation: $success")
            return success
        }

        return try {
            doAttempt(targetUrlStr)
        } catch (e: java.net.UnknownHostException) {
            logError("Logout failed with UnknownHostException.", e)
            if (fallbackIp != null) {
                val fallbackTargetUrlStr = targetUrlStr.replace("phc.prontonetworks.com", fallbackIp)
                logDebug("Retrying logout with fallback IP: $fallbackTargetUrlStr")
                try {
                    doAttempt(fallbackTargetUrlStr)
                } catch (fallbackE: Exception) {
                    logError("Fallback logout failed with exception: ${fallbackE.message}", fallbackE)
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