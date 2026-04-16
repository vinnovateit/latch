package com.vinnovateit.latch.common.debug

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DebugRuntimeLogger {
    private const val ENDPOINT = "http://127.0.0.1:7890/ingest/f1f0e42b-5ad9-4c82-96b5-7103945bc970"
    private const val SESSION_ID = "5d756a"

    fun log(
        runId: String,
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap()
    ) {
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("sessionId", SESSION_ID)
                    put("runId", runId)
                    put("hypothesisId", hypothesisId)
                    put("location", location)
                    put("message", message)
                    put("timestamp", System.currentTimeMillis())
                    put("data", JSONObject(data))
                }

                val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 1500
                    readTimeout = 1500
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Debug-Session-Id", SESSION_ID)
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                connection.responseCode
                connection.disconnect()
            } catch (_: Exception) {
                // ignore - never break app flow for debug logging
            }
        }.start()
    }
}
