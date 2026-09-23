package com.vinnovateit.latch.core.runtime

import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private const val ONE_SHOT_ACQUIRE_TIMEOUT_MS = 1_500L
private const val ONE_SHOT_RETRY_DELAY_MS = 75L

/**
 * A normal one-shot CLI command (`--status`, `--login`, ...) can start at the
 * exact moment another process is still writing its owner metadata/token, or
 * while a dying owner still holds the runtime lock. That window is real but
 * narrow and self-resolving -- unlike [claimDesktopOwnership]'s ~10s wait for
 * an interactive daemon startup, an interactive one-shot command only needs
 * to outlast it, not wait out an entire activation.
 *
 * This retries [InstanceCoordinator.tryAcquire] only while the failure is
 * [AcquisitionFailureReason.isTransitional]; a definitive failure (bad
 * permissions, an incompatible peer, an I/O error) returns on the first try.
 */
suspend fun tryAcquireOneShot(
    dataDir: File,
    ownerKind: OwnerKind,
    timeoutMillis: Long = ONE_SHOT_ACQUIRE_TIMEOUT_MS,
    retryDelayMillis: Long = ONE_SHOT_RETRY_DELAY_MS,
    handler: suspend (InstanceRequest) -> InstanceResponse,
): AcquireResult {
    val deadline = System.nanoTime() + timeoutMillis.milliseconds.inWholeNanoseconds
    while (true) {
        val result = InstanceCoordinator.tryAcquire(dataDir, ownerKind, handler)
        val retryable = result is AcquireResult.Failure && result.reason.isTransitional
        if (!retryable || System.nanoTime() > deadline) return result
        delay(retryDelayMillis)
    }
}
