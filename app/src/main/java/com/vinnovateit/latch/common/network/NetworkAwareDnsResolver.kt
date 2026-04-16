package com.vinnovateit.latch.common.network

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import com.vinnovateit.latch.common.debug.DebugRuntimeLogger
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object NetworkAwareDnsResolver {
    fun resolveFirst(hostname: String, network: Network?, timeoutMs: Long = 2500L): InetAddress {
        // #region agent log
        DebugRuntimeLogger.log(
            runId = "pre-fix",
            hypothesisId = "G",
            location = "NetworkAwareDnsResolver.kt:resolveFirst",
            message = "Resolver entry",
            data = mapOf(
                "hostname" to hostname,
                "hasNetwork" to (network != null),
                "network" to (network?.toString() ?: "null"),
                "sdkInt" to Build.VERSION.SDK_INT
            )
        )
        // #endregion
        if (network == null) {
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "G",
                location = "NetworkAwareDnsResolver.kt:resolveFirst",
                message = "Network null; using InetAddress.getByName fallback",
                data = mapOf("hostname" to hostname)
            )
            // #endregion
            return InetAddress.getByName(hostname)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "G",
                location = "NetworkAwareDnsResolver.kt:resolveFirst",
                message = "SDK below Q; using network.getAllByName fallback",
                data = mapOf("hostname" to hostname, "network" to network.toString())
            )
            // #endregion
            return network.getAllByName(hostname).firstOrNull()
                ?: throw UnknownHostException("No address for $hostname")
        }

        val addressesRef = AtomicReference<List<InetAddress>?>(null)
        val errorRef = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val cancellationSignal = CancellationSignal()

        try {
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "G",
                location = "NetworkAwareDnsResolver.kt:resolveFirst",
                message = "Calling DnsResolver.query",
                data = mapOf("hostname" to hostname, "network" to network.toString(), "timeoutMs" to timeoutMs)
            )
            // #endregion
            DnsResolver.getInstance().query(
                network,
                hostname,
                DnsResolver.FLAG_NO_CACHE_LOOKUP,
                executor,
                cancellationSignal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        addressesRef.set(answer)
                        // #region agent log
                        DebugRuntimeLogger.log(
                            runId = "pre-fix",
                            hypothesisId = "G",
                            location = "NetworkAwareDnsResolver.kt:onAnswer",
                            message = "DnsResolver answer received",
                            data = mapOf(
                                "hostname" to hostname,
                                "rcode" to rcode,
                                "answerCount" to answer.size
                            )
                        )
                        // #endregion
                        latch.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        errorRef.set(error)
                        // #region agent log
                        DebugRuntimeLogger.log(
                            runId = "pre-fix",
                            hypothesisId = "G",
                            location = "NetworkAwareDnsResolver.kt:onError",
                            message = "DnsResolver error",
                            data = mapOf(
                                "hostname" to hostname,
                                "errorCode" to error.code,
                                "errorMessage" to (error.message ?: "null")
                            )
                        )
                        // #endregion
                        latch.countDown()
                    }
                }
            )

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                cancellationSignal.cancel()
                // #region agent log
                DebugRuntimeLogger.log(
                    runId = "pre-fix",
                    hypothesisId = "G",
                    location = "NetworkAwareDnsResolver.kt:resolveFirst",
                    message = "DnsResolver timeout",
                    data = mapOf("hostname" to hostname, "timeoutMs" to timeoutMs)
                )
                // #endregion
                throw UnknownHostException("DNS timeout for $hostname")
            }
        } finally {
            executor.shutdownNow()
        }

        val error = errorRef.get()
        if (error != null) {
            throw UnknownHostException("DNS failed for $hostname: ${error.message}")
        }

        val resolved = addressesRef.get()?.firstOrNull()
        // #region agent log
        DebugRuntimeLogger.log(
            runId = "pre-fix",
            hypothesisId = "G",
            location = "NetworkAwareDnsResolver.kt:resolveFirst",
            message = "Resolver returning",
            data = mapOf(
                "hostname" to hostname,
                "resolvedIp" to (resolved?.hostAddress ?: "null")
            )
        )
        // #endregion
        return resolved
            ?: throw UnknownHostException("No address for $hostname")
    }
}
