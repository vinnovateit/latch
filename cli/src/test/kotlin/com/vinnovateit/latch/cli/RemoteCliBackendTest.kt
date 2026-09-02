package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommandService
import com.vinnovateit.latch.core.runtime.RuntimeCommandTarget
import com.vinnovateit.latch.core.runtime.RuntimeOperation
import com.vinnovateit.latch.core.runtime.RuntimeSessionRecord
import com.vinnovateit.latch.core.runtime.RuntimeSettingsSnapshot
import com.vinnovateit.latch.core.runtime.RuntimeSnapshot
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class RemoteCliBackendTest {
    @Test
    fun `remote backend maps status history and settings`() = runBlocking {
        withRemoteBackend { backend, _ ->
            assertEquals(OperationResult(true), backend.isSetup())
            assertEquals(
                OperationResult(CliStatus("desktop", "connected", "VIT", true)),
                backend.status(),
            )
            assertEquals(
                OperationResult(listOf(CliSession(1, 2, 3, 4, 5, 6))),
                backend.history(),
            )
            assertEquals(
                OperationResult(CliSettings(true, setOf("G-VIT", "VIT"))),
                backend.settings(),
            )
        }
    }

    @Test
    fun `remote backend forwards mutations and credentials`() = runBlocking {
        withRemoteBackend { backend, target ->
            val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')

            assertEquals(OperationResult(Unit), backend.setAutoLogin(false))
            assertEquals(OperationResult(Unit), backend.setAllowedSsids(setOf("VIT", "G-VIT")))
            assertEquals(OperationResult(Unit), backend.setCredentials("22BCE0001", password))

            assertEquals(false, target.autoLogin)
            assertEquals(setOf("VIT", "G-VIT"), target.ssids)
            assertEquals("22BCE0001", target.userId)
            assertEquals("secret", target.password)
            assertContentEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't'), password)
        }
    }

    @Test
    fun `remote operational errors are preserved`() = runBlocking {
        val target = RemoteTarget(loginOperation = RuntimeOperation(false, "NO_WIFI", "Wi-Fi is unavailable."))
        withRemoteBackend(target) { backend, _ ->
            assertEquals(OperationResult<Unit>(error = "Wi-Fi is unavailable."), backend.login())
        }
    }

    private suspend fun withRemoteBackend(
        target: RemoteTarget = RemoteTarget(),
        block: suspend (RemoteCliBackend, RemoteTarget) -> Unit,
    ) {
        val directory = createTempDirectory("latch-remote-cli-").toFile()
        val service = RuntimeCommandService(OwnerKind.DESKTOP, target)
        val owner = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, service::execute),
        )
        try {
            val existing = assertIs<AcquireResult.Existing>(
                InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_ONESHOT, service::execute),
            )
            block(RemoteCliBackend(existing.client), target)
        } finally {
            owner.coordinator.close()
            directory.deleteRecursively()
        }
    }
}

private class RemoteTarget(
    private val loginOperation: RuntimeOperation = RuntimeOperation(true),
) : RuntimeCommandTarget {
    var autoLogin: Boolean? = null
    var ssids: Set<String>? = null
    var userId: String? = null
    var password: String? = null

    override suspend fun isSetup() = true
    override suspend fun snapshot() = RuntimeSnapshot("connected", "VIT", true)
    override suspend fun login() = loginOperation
    override suspend fun logout() = RuntimeOperation(true)
    override suspend fun history() = listOf(RuntimeSessionRecord(1, 2, 3, 4, 5, 6))
    override suspend fun settings() = RuntimeSettingsSnapshot(true, setOf("VIT", "G-VIT"))
    override suspend fun setAutoLogin(enabled: Boolean) { autoLogin = enabled }
    override suspend fun setAllowedSsids(values: Set<String>) { ssids = values }
    override suspend fun setCredentials(userId: String, password: String) {
        this.userId = userId
        this.password = password
    }
}
