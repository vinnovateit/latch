package com.vinnovateit.latch.core.wifi

import kotlinx.coroutines.flow.StateFlow

interface HostelConnectivityMonitor {
    val state: StateFlow<HostelConnectivitySnapshot>
    fun start()
    fun stop()
    suspend fun evaluateNow()
}
