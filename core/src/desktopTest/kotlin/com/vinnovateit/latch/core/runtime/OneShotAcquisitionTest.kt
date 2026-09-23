package com.vinnovateit.latch.core.runtime

import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OneShotAcquisitionTest {
    @Test
    fun `metadata becoming available shortly after the lock is held resolves to the existing owner`() = runBlocking {
        val directory = createTempDirectory("latch-oneshot-metadata-").toFile()
        val files = SecureRuntimeFiles(directory)
        val heldChannel = RandomAccessFile(files.lockFile, "rw").channel
        val heldLock = checkNotNull(heldChannel.tryLock()) { "test setup: expected to acquire the lock" }
        try {
            launch(Dispatchers.IO) {
                delay(150)
                files.writeToken("transitional-token")
                files.writeMetadata(
                    OwnerMetadata(
                        version = INSTANCE_PROTOCOL_VERSION,
                        ownerKind = OwnerKind.DESKTOP,
                        port = 1,
                        pid = ProcessHandle.current().pid(),
                        startedAt = 1,
                    ),
                )
            }

            val result = tryAcquireOneShot(directory, OwnerKind.CLI_ONESHOT, handler = ::echo)

            assertIs<AcquireResult.Existing>(result)
            Unit
        } finally {
            runCatching { heldLock.release() }
            heldChannel.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a token becoming available shortly after metadata resolves to the existing owner`() = runBlocking {
        val directory = createTempDirectory("latch-oneshot-token-").toFile()
        val files = SecureRuntimeFiles(directory)
        val heldChannel = RandomAccessFile(files.lockFile, "rw").channel
        val heldLock = checkNotNull(heldChannel.tryLock()) { "test setup: expected to acquire the lock" }
        try {
            files.writeMetadata(
                OwnerMetadata(
                    version = INSTANCE_PROTOCOL_VERSION,
                    ownerKind = OwnerKind.DESKTOP,
                    port = 1,
                    pid = ProcessHandle.current().pid(),
                    startedAt = 1,
                ),
            )
            launch(Dispatchers.IO) {
                delay(150)
                files.writeToken("transitional-token")
            }

            val result = tryAcquireOneShot(directory, OwnerKind.CLI_ONESHOT, handler = ::echo)

            assertIs<AcquireResult.Existing>(result)
            Unit
        } finally {
            runCatching { heldLock.release() }
            heldChannel.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a transitional owner disappearing lets the caller become owner`() = runBlocking {
        val directory = createTempDirectory("latch-oneshot-transition-").toFile()
        val files = SecureRuntimeFiles(directory)
        val heldChannel = RandomAccessFile(files.lockFile, "rw").channel
        val heldLock = checkNotNull(heldChannel.tryLock()) { "test setup: expected to acquire the lock" }
        launch(Dispatchers.IO) {
            delay(150)
            runCatching { heldLock.release() }
            heldChannel.close()
        }

        val result = tryAcquireOneShot(directory, OwnerKind.CLI_ONESHOT, handler = ::echo)

        val owner = assertIs<AcquireResult.Owner>(result)
        owner.coordinator.close()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `an incompatible protocol fails promptly without waiting out the retry budget`() = runBlocking {
        val directory = createTempDirectory("latch-oneshot-incompatible-").toFile()
        val files = SecureRuntimeFiles(directory)
        val heldChannel = RandomAccessFile(files.lockFile, "rw").channel
        val heldLock = checkNotNull(heldChannel.tryLock()) { "test setup: expected to acquire the lock" }
        try {
            files.writeToken("some-token")
            files.writeMetadata(
                OwnerMetadata(
                    version = INSTANCE_PROTOCOL_VERSION + 1,
                    ownerKind = OwnerKind.DESKTOP,
                    port = 1,
                    pid = ProcessHandle.current().pid(),
                    startedAt = 1,
                ),
            )

            lateinit var result: AcquireResult
            val elapsed = measureTime {
                result = tryAcquireOneShot(directory, OwnerKind.CLI_ONESHOT, timeoutMillis = 1_500, retryDelayMillis = 75, handler = ::echo)
            }

            val failure = assertIs<AcquireResult.Failure>(result)
            assertEquals(AcquisitionFailureReason.INCOMPATIBLE_PROTOCOL, failure.reason)
            assertTrue(elapsed.inWholeMilliseconds < 500, "a definitive failure must not wait out the retry budget, took ${elapsed.inWholeMilliseconds}ms")
        } finally {
            runCatching { heldLock.release() }
            heldChannel.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an unrecoverable io error returns promptly without retrying`() = runBlocking {
        val directory = createTempDirectory("latch-oneshot-ioerror-").toFile()
        val files = SecureRuntimeFiles(directory)
        // A directory where the lock file should be: RandomAccessFile cannot open it.
        files.lockFile.mkdirs()

        lateinit var result: AcquireResult
        val elapsed = measureTime {
            result = tryAcquireOneShot(directory, OwnerKind.CLI_ONESHOT, timeoutMillis = 1_500, retryDelayMillis = 75, handler = ::echo)
        }

        val failure = assertIs<AcquireResult.Failure>(result)
        assertEquals(AcquisitionFailureReason.IO_ERROR, failure.reason)
        assertTrue(elapsed.inWholeMilliseconds < 500, "a definitive failure must not wait out the retry budget, took ${elapsed.inWholeMilliseconds}ms")
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `a failed acquisition leaves no lock behind for the next attempt`() = runBlocking {
        val directory = createTempDirectory("latch-oneshot-cleanup-").toFile()
        val files = SecureRuntimeFiles(directory)
        val heldChannel = RandomAccessFile(files.lockFile, "rw").channel
        val heldLock = checkNotNull(heldChannel.tryLock()) { "test setup: expected to acquire the lock" }
        // Metadata is never written, so every retry observes the same transitional
        // failure until the short budget runs out.
        val result = tryAcquireOneShot(directory, OwnerKind.CLI_ONESHOT, timeoutMillis = 300, retryDelayMillis = 50, handler = ::echo)
        assertIs<AcquireResult.Failure>(result)
        runCatching { heldLock.release() }
        heldChannel.close()

        val owner = assertIs<AcquireResult.Owner>(InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_ONESHOT, ::echo))
        owner.coordinator.close()
        directory.deleteRecursively()
        Unit
    }

    private suspend fun echo(request: InstanceRequest): InstanceResponse = InstanceResponse(
        requestId = request.requestId,
        ok = true,
        code = "OK",
        data = request.arguments,
    )
}
