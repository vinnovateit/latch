package com.vinnovateit.latch.core.mp

/** Minimal HTTP response carried across the platform boundary. */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** A network-bound HTTP client: `handle` identifies the Wi-Fi interface on Android/platforms that support it. */
interface MpHttpClient {
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
        followRedirects: Boolean = false,
        handle: Any? = null,
    ): HttpResponse
}

/** Installed once from the platform layer. */
object MpHttp {
    lateinit var client: MpHttpClient
        private set

    fun install(client: MpHttpClient) { this.client = client }
}
