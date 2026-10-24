package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.NetworkHandle

sealed interface HostelAuthResult {
    data object Success : HostelAuthResult
    data class Failure(val reason: String) : HostelAuthResult
    data object SkippedCooldown : HostelAuthResult
}

interface HostelAuthenticator {
    suspend fun authenticate(networkHandle: NetworkHandle? = null): HostelAuthResult
}
