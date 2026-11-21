package com.vinnovateit.latch.core.mp

class JvmHttpClient : MpHttpClient {
    override fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean,
        handle: Any?,
    ): HttpResponse {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = followRedirects
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            return HttpResponse(code, stream?.bufferedReader()?.readText().orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
