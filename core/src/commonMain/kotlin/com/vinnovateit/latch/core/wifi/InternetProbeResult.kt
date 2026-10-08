package com.vinnovateit.latch.core.wifi

/**
 * Detailed outcome of an active Internet probe.
 */
data class InternetProbeResult(
    val reachable: Boolean,
    val captivePortalSuspected: Boolean,
    val latencyMs: Long,
    val statusCode: Int? = null,
    val redirectDetected: Boolean = false,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
