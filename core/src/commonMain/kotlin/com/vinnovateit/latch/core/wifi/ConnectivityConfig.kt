package com.vinnovateit.latch.core.wifi

data class ConnectivityConfig(
    val latencyWarningMs: Long = 1500L,
    val latencyCriticalMs: Long = 5000L,
    val probeTimeoutMs: Long = 5000L,
    val debounceMs: Long = 500L,
    val connectedIntervalMs: Long = 25_000L,
    val degradedIntervalMs: Long = 5_000L,
    val authCooldownMs: Long = 15_000L,
    val maxRevalidateRetries: Int = 3,
    val revalidateDelayMs: Long = 2000L,
    val maxConsecutiveFailures: Int = 3,
)
