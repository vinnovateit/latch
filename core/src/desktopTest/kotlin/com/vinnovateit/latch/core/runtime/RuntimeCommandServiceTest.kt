package com.vinnovateit.latch.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RuntimeCommandServiceTest {
    @Test
    fun `ping identifies the active owner`() = runBlocking {
        val response = service().execute(request(RuntimeCommand.PING))

        assertTrue(response.ok)
        assertEquals("OK", response.code)
        assertEquals("cli-daemon", response.data["owner"])
    }

    @Test
    fun `setup status reports whether credentials exist`() = runBlocking {
        val configured = service(FakeRuntimeTarget(setup = true)).execute(request(RuntimeCommand.SETUP_STATUS))
        val unconfigured = service(FakeRuntimeTarget(setup = false)).execute(request(RuntimeCommand.SETUP_STATUS))

        assertEquals("true", configured.data["configured"])
        assertEquals("false", unconfigured.data["configured"])
    }

    @Test
    fun `deactivate stops only a cli daemon owner`() = runBlocking {
        var stopped = false
        val cliService = RuntimeCommandService(
            ownerKind = OwnerKind.CLI_DAEMON,
            target = FakeRuntimeTarget(),
            onDeactivate = { stopped = true; true },
        )
        val desktopService = RuntimeCommandService(
            ownerKind = OwnerKind.DESKTOP,
            target = FakeRuntimeTarget(),
            onDeactivate = { true },
        )

        val cliResponse = cliService.execute(request(RuntimeCommand.DEACTIVATE))
        val desktopResponse = desktopService.execute(request(RuntimeCommand.DEACTIVATE))

        assertTrue(cliResponse.ok)
        assertTrue(stopped)
        assertEquals("OWNER_CHANGED", desktopResponse.code)
    }

    @Test
    fun `status and history are serialized for clients`() = runBlocking {
        val target = FakeRuntimeTarget(
            snapshot = RuntimeSnapshot("connected", "VIT", true),
            sessionValues = listOf(RuntimeSessionRecord(1, 2, 3, 4, 5, 6)),
        )
        val service = service(target)

        val status = service.execute(request(RuntimeCommand.STATUS))
        val history = service.execute(request(RuntimeCommand.HISTORY))

        assertEquals(mapOf("owner" to "cli-daemon", "connection" to "connected", "ssid" to "VIT", "latched" to "true"), status.data)
        assertTrue(history.data.getValue("sessions").contains("\"start\":1"))
    }

    @Test
    fun `settings can be read and changed through allowlisted keys`() = runBlocking {
        val target = FakeRuntimeTarget(settingsValue = RuntimeSettingsSnapshot(true, setOf("VIT")))
        val service = service(target)

        val settings = service.execute(request(RuntimeCommand.GET_SETTINGS))
        val autoLogin = service.execute(
            request(RuntimeCommand.SET_SETTING, mapOf("key" to "auto-login", "value" to "off")),
        )
        val ssids = service.execute(
            request(RuntimeCommand.SET_SETTING, mapOf("key" to "allowed-ssids", "value" to "VIT,G-VIT")),
        )

        assertEquals("true", settings.data["autoLogin"])
        assertEquals("[\"VIT\"]", settings.data["allowedSsids"])
        assertTrue(autoLogin.ok)
        assertEquals(false, target.autoLoginValue)
        assertTrue(ssids.ok)
        assertEquals(setOf("VIT", "G-VIT"), target.allowedSsidsValue)
    }

    @Test
    fun `unknown and malformed settings fail validation`() = runBlocking {
        val service = service()

        val unknown = service.execute(
            request(RuntimeCommand.SET_SETTING, mapOf("key" to "theme", "value" to "dark")),
        )
        val invalidToggle = service.execute(
            request(RuntimeCommand.SET_SETTING, mapOf("key" to "auto-login", "value" to "yes")),
        )
        val emptySsid = service.execute(
            request(RuntimeCommand.SET_SETTING, mapOf("key" to "allowed-ssids", "value" to "VIT,")),
        )

        assertEquals("INVALID_ARGUMENT", unknown.code)
        assertEquals("INVALID_ARGUMENT", invalidToggle.code)
        assertEquals("INVALID_ARGUMENT", emptySsid.code)
    }

    @Test
    fun `credentials require both fields and are never echoed`() = runBlocking {
        val target = FakeRuntimeTarget()
        val service = service(target)

        val response = service.execute(
            request(RuntimeCommand.SET_CREDENTIALS, mapOf("userId" to "22BCE0001", "password" to "secret")),
        )
        val invalid = service.execute(
            request(RuntimeCommand.SET_CREDENTIALS, mapOf("userId" to "22BCE0001", "password" to "")),
        )

        assertTrue(response.ok)
        assertEquals("22BCE0001", target.credentialUserId)
        assertEquals("secret", target.credentialPassword)
        assertFalse(response.toString().contains("secret"))
        assertEquals("INVALID_ARGUMENT", invalid.code)
    }

    @Test
    fun `engine operation errors retain stable codes`() = runBlocking {
        val target = FakeRuntimeTarget(
            loginResult = RuntimeOperation(false, "NO_WIFI", "Wi-Fi is unavailable."),
            logoutResult = RuntimeOperation(false, "TIMEOUT", "Logout timed out."),
        )
        val service = service(target)

        val login = service.execute(request(RuntimeCommand.LOGIN))
        val logout = service.execute(request(RuntimeCommand.LOGOUT))

        assertEquals("NO_WIFI", login.code)
        assertEquals("TIMEOUT", logout.code)
    }

    @Test
    fun `activation and takeover invoke owner callbacks`() = runBlocking {
        var activated = false
        var takeover = false
        val service = RuntimeCommandService(
            ownerKind = OwnerKind.CLI_DAEMON,
            target = FakeRuntimeTarget(),
            onActivateUi = { activated = true },
            onTakeOver = { takeover = true; true },
        )

        val activation = service.execute(request(RuntimeCommand.ACTIVATE_UI))
        val handoff = service.execute(request(RuntimeCommand.TAKE_OVER))

        assertTrue(activation.ok)
        assertTrue(activated)
        assertTrue(handoff.ok)
        assertTrue(takeover)
    }

    @Test
    fun `service rejects protocol mismatch`() = runBlocking {
        val mismatched = request(RuntimeCommand.PING).copy(version = INSTANCE_PROTOCOL_VERSION + 1)

        val response = service().execute(mismatched)

        assertEquals("PROTOCOL_MISMATCH", response.code)
    }

    private fun service(target: RuntimeCommandTarget = FakeRuntimeTarget()) =
        RuntimeCommandService(OwnerKind.CLI_DAEMON, target)

    private fun request(command: RuntimeCommand, arguments: Map<String, String> = emptyMap()) = InstanceRequest(
        version = INSTANCE_PROTOCOL_VERSION,
        token = "validated-by-coordinator",
        requestId = "request-1",
        command = command,
        arguments = arguments,
    )
}

private class FakeRuntimeTarget(
    private val snapshot: RuntimeSnapshot = RuntimeSnapshot("idle", null, false),
    private val sessionValues: List<RuntimeSessionRecord> = emptyList(),
    private val settingsValue: RuntimeSettingsSnapshot = RuntimeSettingsSnapshot(true, setOf("VIT")),
    private val loginResult: RuntimeOperation = RuntimeOperation(true),
    private val logoutResult: RuntimeOperation = RuntimeOperation(true),
    private val setup: Boolean = true,
) : RuntimeCommandTarget {
    var autoLoginValue: Boolean? = null
    var allowedSsidsValue: Set<String>? = null
    var credentialUserId: String? = null
    var credentialPassword: String? = null

    override suspend fun snapshot(): RuntimeSnapshot = snapshot
    override suspend fun isSetup(): Boolean = setup
    override suspend fun login(): RuntimeOperation = loginResult
    override suspend fun logout(): RuntimeOperation = logoutResult
    override suspend fun history(): List<RuntimeSessionRecord> = sessionValues
    override suspend fun settings(): RuntimeSettingsSnapshot = settingsValue
    override suspend fun setAutoLogin(enabled: Boolean) { autoLoginValue = enabled }
    override suspend fun setAllowedSsids(values: Set<String>) { allowedSsidsValue = values }
    override suspend fun setCredentials(userId: String, password: String) {
        credentialUserId = userId
        credentialPassword = password
    }
}
