package com.vinnovateit.latch.core.runtime

import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

sealed interface DesktopOwnership {
    data class Owner(val coordinator: InstanceCoordinator) : DesktopOwnership
    data object ActivatedExisting : DesktopOwnership
    data class Failure(val message: String) : DesktopOwnership
}

suspend fun claimDesktopOwnership(
    dataDir: File,
    timeoutMillis: Long = 10_000,
    retryDelayMillis: Long = 100,
    handler: suspend (InstanceRequest) -> InstanceResponse,
): DesktopOwnership {
    val deadline = System.nanoTime() + timeoutMillis.milliseconds.inWholeNanoseconds
    var takeoverRequested = false
    var lastFailure = "Timed out waiting for the active Latch instance."

    while (System.nanoTime() <= deadline) {
        when (val acquired = InstanceCoordinator.tryAcquire(dataDir, OwnerKind.DESKTOP, handler)) {
            is AcquireResult.Owner -> return DesktopOwnership.Owner(acquired.coordinator)
            is AcquireResult.Failure -> lastFailure = acquired.message
            is AcquireResult.Existing -> when (acquired.metadata.ownerKind) {
                OwnerKind.DESKTOP -> {
                    val response = acquired.client.send(RuntimeCommand.ACTIVATE_UI)
                    return if (response.ok) DesktopOwnership.ActivatedExisting
                    else DesktopOwnership.Failure(response.message.ifBlank { response.code })
                }
                OwnerKind.CLI_DAEMON -> if (!takeoverRequested) {
                    val response = acquired.client.send(RuntimeCommand.TAKE_OVER)
                    if (!response.ok) {
                        return DesktopOwnership.Failure(response.message.ifBlank { response.code })
                    }
                    takeoverRequested = true
                }
                OwnerKind.CLI_ONESHOT -> Unit
            }
        }
        delay(retryDelayMillis)
    }

    return DesktopOwnership.Failure(lastFailure)
}
