package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.desktop.AppPaths
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class InstanceCoordinatorTest {
    @Test
    fun `protocol request and response round trip through json`() {
        val request = InstanceRequest(
            version = INSTANCE_PROTOCOL_VERSION,
            token = "secret",
            requestId = "request-1",
            command = RuntimeCommand.SET_SETTING,
            arguments = mapOf("key" to "auto-login", "value" to "on"),
        )
        val response = InstanceResponse(
            requestId = "request-1",
            ok = true,
            code = "OK",
            data = mapOf("owner" to "desktop"),
        )

        assertEquals(request, Json.decodeFromString<InstanceRequest>(Json.encodeToString(request)))
        assertEquals(response, Json.decodeFromString<InstanceResponse>(Json.encodeToString(response)))
    }

    @Test
    fun `app paths honor isolated data directory override`() {
        val temporary = createTempDirectory("latch-paths-").toFile()
        val previous = System.getProperty("latch.dataDir")
        try {
            System.setProperty("latch.dataDir", temporary.absolutePath)
            assertEquals(temporary.canonicalFile, AppPaths.dataDir.canonicalFile)
        } finally {
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            temporary.deleteRecursively()
        }
    }

    @Test
    fun `runtime token is random and owner only`() {
        val directory = createTempDirectory("latch-token-").toFile()
        try {
            val files = SecureRuntimeFiles(directory)
            val first = files.createToken()
            val second = files.createToken()

            assertNotEquals(first, second)
            assertTrue(first.length >= 40)
            if (Files.getFileStore(files.tokenFile.toPath()).supportsFileAttributeView("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(files.tokenFile.toPath()),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `exactly one coordinator owns a runtime directory`() {
        val directory = createTempDirectory("latch-owner-").toFile()
        val first = InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_DAEMON, ::echo)
        try {
            val second = InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo)
            assertIs<AcquireResult.Owner>(first)
            assertIs<AcquireResult.Existing>(second)
        } finally {
            (first as? AcquireResult.Owner)?.coordinator?.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `authenticated client reaches owner`() = runBlocking {
        val directory = createTempDirectory("latch-auth-").toFile()
        val acquired = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo),
        )
        try {
            val existing = assertIs<AcquireResult.Existing>(
                InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_ONESHOT, ::echo),
            )

            val response = existing.client.send(RuntimeCommand.PING, mapOf("message" to "hello"))

            assertTrue(response.ok)
            assertEquals("OK", response.code)
            assertEquals("hello", response.data["message"])
        } finally {
            acquired.coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `wrong token fails closed`() = runBlocking {
        val directory = createTempDirectory("latch-bad-token-").toFile()
        val acquired = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo),
        )
        try {
            val response = InstanceClient(acquired.coordinator.port, "wrong-token")
                .send(RuntimeCommand.PING)

            assertEquals(false, response.ok)
            assertEquals("UNAUTHORIZED", response.code)
        } finally {
            acquired.coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `malformed request receives a bounded error`() = runBlocking {
        val directory = createTempDirectory("latch-malformed-").toFile()
        val acquired = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo),
        )
        try {
            val response = InstanceClient(acquired.coordinator.port, "unused").sendRaw("not-json")
            assertEquals(false, response.ok)
            assertEquals("MALFORMED_REQUEST", response.code)
        } finally {
            acquired.coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `oversized request is rejected before parsing`() = runBlocking {
        val directory = createTempDirectory("latch-large-").toFile()
        val acquired = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo),
        )
        try {
            val oversized = "x".repeat(MAX_INSTANCE_REQUEST_BYTES + 1)
            val response = InstanceClient(acquired.coordinator.port, "unused").sendRaw(oversized)
            assertEquals(false, response.ok)
            assertEquals("PAYLOAD_TOO_LARGE", response.code)
        } finally {
            acquired.coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `stale runtime metadata is replaced by a new owner`() {
        val directory = createTempDirectory("latch-stale-").toFile()
        val files = SecureRuntimeFiles(directory)
        files.writeToken("stale-token")
        files.writeMetadata(
            OwnerMetadata(
                version = INSTANCE_PROTOCOL_VERSION,
                ownerKind = OwnerKind.CLI_DAEMON,
                port = 1,
                pid = Long.MAX_VALUE,
                startedAt = 1,
            ),
        )

        val acquired = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo),
        )
        try {
            val current = files.readMetadata()
            assertEquals(OwnerKind.DESKTOP, current?.ownerKind)
            assertEquals(ProcessHandle.current().pid(), current?.pid)
            assertNotEquals("stale-token", files.readToken())
        } finally {
            acquired.coordinator.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun echo(request: InstanceRequest): InstanceResponse = InstanceResponse(
        requestId = request.requestId,
        ok = true,
        code = "OK",
        data = request.arguments,
    )
}
