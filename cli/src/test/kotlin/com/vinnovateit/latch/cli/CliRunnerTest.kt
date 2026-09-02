package com.vinnovateit.latch.cli

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliRunnerTest {
    @Test
    fun `usage failure exits two without creating a backend`() = runBlocking {
        val terminal = RecordingTerminal()
        var backendCreated = false

        val exitCode = runCli(arrayOf("--unknown"), terminal) {
            backendCreated = true
            FakeBackend()
        }

        assertEquals(2, exitCode)
        assertFalse(backendCreated)
        assertTrue(terminal.output.startsWith("error: Unknown command: --unknown\n"))
        assertTrue(terminal.output.contains("Usage: latch-cli [command]"))
    }

    @Test
    fun `help prints usage without creating a backend`() = runBlocking {
        val terminal = RecordingTerminal()
        var backendCreated = false

        val exitCode = CliRunner(terminal, {
            backendCreated = true
            FakeBackend()
        }).run(CliCommand.Help)

        assertEquals(0, exitCode)
        assertFalse(backendCreated)
        assertTrue(terminal.output.contains("Usage: latch-cli [command]"))
        assertTrue(terminal.output.contains("--history"))
        assertTrue(terminal.output.contains("--settings set auto-login <on|off>"))
    }

    @Test
    fun `version prints version without creating a backend`() = runBlocking {
        val terminal = RecordingTerminal()
        var backendCreated = false

        val exitCode = CliRunner(terminal, {
            backendCreated = true
            FakeBackend()
        }, version = "9.8.7").run(CliCommand.Version)

        assertEquals(0, exitCode)
        assertFalse(backendCreated)
        assertEquals("latch-cli 9.8.7\n", terminal.output)
    }

    @Test
    fun `configured bootstrap prints help and does not activate`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend(setupResult = OperationResult(true))
        val lifecycle = FakeLifecycle()

        val exitCode = CliRunner(terminal, { backend }, lifecycle = lifecycle).run(CliCommand.Bootstrap)

        assertEquals(0, exitCode)
        assertTrue(terminal.output.contains("Usage: latch-cli [command]"))
        assertFalse(lifecycle.activated)
        assertTrue(backend.closed)
    }

    @Test
    fun `first bootstrap saves credentials and activates background daemon`() = runBlocking {
        val secret = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val terminal = RecordingTerminal(lines = ArrayDeque(listOf("22BCE0001")), secrets = ArrayDeque(listOf(secret)))
        val backend = FakeBackend(setupResult = OperationResult(false))
        val lifecycle = FakeLifecycle()

        val exitCode = CliRunner(terminal, { backend }, lifecycle = lifecycle).run(CliCommand.Bootstrap)

        assertEquals(0, exitCode)
        assertEquals("22BCE0001", backend.credentialUserId)
        assertTrue(lifecycle.activated)
        assertTrue(terminal.output.contains("Welcome to Latch."))
        assertTrue(terminal.output.contains("Latch is running in the background and will start when you log in."))
    }

    @Test
    fun `activate and deactivate use persistent lifecycle without backend`() = runBlocking {
        val terminal = RecordingTerminal()
        var backendCreated = false
        val lifecycle = FakeLifecycle()
        val runner = CliRunner(terminal, {
            backendCreated = true
            FakeBackend()
        }, lifecycle = lifecycle)

        assertEquals(0, runner.run(CliCommand.Activate))
        assertEquals(0, runner.run(CliCommand.Deactivate))

        assertFalse(backendCreated)
        assertTrue(lifecycle.activated)
        assertTrue(lifecycle.deactivated)
        assertTrue(terminal.output.contains("Latch is running in the background and will start when you log in."))
        assertTrue(terminal.output.contains("Latch background daemon stopped and login startup disabled."))
    }

    @Test
    fun `status has stable human-readable output and closes backend`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend(
            statusResult = OperationResult(
                CliStatus(owner = "desktop", connection = "online", ssid = "VIT", latched = true),
            ),
        )

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.Status)

        assertEquals(0, exitCode)
        assertEquals(
            "owner: desktop\nconnection: online\nssid: VIT\nlatched: yes\n",
            terminal.output,
        )
        assertTrue(backend.closed)
    }

    @Test
    fun `missing ssid is rendered explicitly`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend(
            statusResult = OperationResult(
                CliStatus(owner = "cli", connection = "disconnected", ssid = null, latched = false),
            ),
        )

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.Status)

        assertEquals(0, exitCode)
        assertTrue(terminal.output.contains("ssid: none\n"))
        assertTrue(terminal.output.contains("latched: no\n"))
    }

    @Test
    fun `operational failure prints error and exits one`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend(statusResult = OperationResult(error = "Wi-Fi is unavailable"))

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.Status)

        assertEquals(1, exitCode)
        assertEquals("error: Wi-Fi is unavailable\n", terminal.output)
        assertTrue(backend.closed)
    }

    @Test
    fun `backend creation failure is an operational error`() = runBlocking {
        val terminal = RecordingTerminal()

        val exitCode = CliRunner(terminal, { error("database unavailable") }).run(CliCommand.Status)

        assertEquals(1, exitCode)
        assertEquals("error: database unavailable\n", terminal.output)
    }

    @Test
    fun `history is newest first with stable timestamps`() = runBlocking {
        val terminal = RecordingTerminal()
        val older = CliSession(start = 1_000, end = 2_000, rx = 10, tx = 20, maxRx = 30, maxTx = 40)
        val newer = CliSession(start = 3_000, end = 4_000, rx = 50, tx = 60, maxRx = 70, maxTx = 80)
        val backend = FakeBackend(historyResult = OperationResult(listOf(older, newer)))

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.History)

        assertEquals(0, exitCode)
        assertEquals(
            "start\tend\trx-bytes\ttx-bytes\tmax-rx-bps\tmax-tx-bps\n" +
                "1970-01-01T00:00:03Z\t1970-01-01T00:00:04Z\t50\t60\t70\t80\n" +
                "1970-01-01T00:00:01Z\t1970-01-01T00:00:02Z\t10\t20\t30\t40\n",
            terminal.output,
        )
    }

    @Test
    fun `empty history says there are no sessions`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend(historyResult = OperationResult(emptyList()))

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.History)

        assertEquals(0, exitCode)
        assertEquals("No sessions.\n", terminal.output)
    }

    @Test
    fun `settings output sorts ssids`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend(
            settingsResult = OperationResult(CliSettings(autoLogin = true, allowedSsids = setOf("VIT", "G-VIT"))),
        )

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.GetSettings)

        assertEquals(0, exitCode)
        assertEquals("auto-login: on\nallowed-ssids: G-VIT,VIT\n", terminal.output)
    }

    @Test
    fun `setting auto-login updates backend`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend()

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.SetAutoLogin(enabled = false))

        assertEquals(0, exitCode)
        assertEquals(false, backend.autoLoginValue)
        assertEquals("auto-login: off\n", terminal.output)
    }

    @Test
    fun `setting allowed ssids updates backend`() = runBlocking {
        val terminal = RecordingTerminal()
        val backend = FakeBackend()

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.SetAllowedSsids(setOf("VIT", "G-VIT")))

        assertEquals(0, exitCode)
        assertEquals(setOf("VIT", "G-VIT"), backend.allowedSsidsValue)
        assertEquals("allowed-ssids: G-VIT,VIT\n", terminal.output)
    }

    @Test
    fun `credentials are prompted securely and password buffer is cleared`() = runBlocking {
        val secret = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val terminal = RecordingTerminal(lines = ArrayDeque(listOf("22BCE0001")), secrets = ArrayDeque(listOf(secret)))
        val backend = FakeBackend()

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.SetCredentials)

        assertEquals(0, exitCode)
        assertEquals("22BCE0001", backend.credentialUserId)
        assertContentEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't'), backend.credentialPassword)
        assertTrue(secret.all { it == '\u0000' })
        assertEquals("Credentials saved.\n", terminal.output)
    }

    @Test
    fun `missing password does not update credentials`() = runBlocking {
        val terminal = RecordingTerminal(lines = ArrayDeque(listOf("22BCE0001")))
        val backend = FakeBackend()

        val exitCode = CliRunner(terminal, { backend }).run(CliCommand.SetCredentials)

        assertEquals(1, exitCode)
        assertEquals(null, backend.credentialUserId)
        assertEquals("error: A password is required.\n", terminal.output)
    }

    @Test
    fun `successful login and logout use stable output`() = runBlocking {
        val cases = mapOf(
            CliCommand.Login to "Login completed.\n",
            CliCommand.Logout to "Logout completed.\n",
        )

        cases.forEach { (command, expectedOutput) ->
            val terminal = RecordingTerminal()
            val exitCode = CliRunner(terminal, { FakeBackend() }).run(command)
            assertEquals(0, exitCode, command.toString())
            assertEquals(expectedOutput, terminal.output, command.toString())
        }
    }

    @Test
    fun `splash runs for interactive bootstrap but not background daemon`() = runBlocking {
        var splashCount = 0
        val splash: suspend (TerminalIO) -> Unit = { splashCount++ }

        CliRunner(RecordingTerminal(), { FakeBackend() }, splash = splash).run(CliCommand.Status)
        assertEquals(0, splashCount)

        CliRunner(RecordingTerminal(), { FakeBackend() }, splash = splash).run(CliCommand.Bootstrap)
        assertEquals(1, splashCount)

        CliRunner(RecordingTerminal(), { FakeBackend() }, splash = splash).run(CliCommand.DaemonProcess)
        assertEquals(1, splashCount)
    }
}

private class RecordingTerminal(
    private val lines: ArrayDeque<String> = ArrayDeque(),
    private val secrets: ArrayDeque<CharArray> = ArrayDeque(),
) : TerminalIO {
    private val buffer = StringBuilder()
    override val interactive: Boolean = true
    val output: String get() = buffer.toString()

    override fun print(text: String) {
        buffer.append(text)
    }

    override fun println(text: String) {
        buffer.append(text).append('\n')
    }

    override fun readLine(prompt: String): String? = lines.removeFirstOrNull()

    override fun readSecret(prompt: String): CharArray? = secrets.removeFirstOrNull()
}

private class FakeBackend(
    private val statusResult: OperationResult<CliStatus> = OperationResult(
        CliStatus(owner = "cli", connection = "idle", ssid = null, latched = false),
    ),
    private val historyResult: OperationResult<List<CliSession>> = OperationResult(emptyList()),
    private val settingsResult: OperationResult<CliSettings> = OperationResult(
        CliSettings(autoLogin = true, allowedSsids = setOf("VIT")),
    ),
    private val setupResult: OperationResult<Boolean> = OperationResult(true),
) : CliBackend {
    var closed = false
    var autoLoginValue: Boolean? = null
    var allowedSsidsValue: Set<String>? = null
    var credentialUserId: String? = null
    var credentialPassword: CharArray? = null

    override suspend fun status(): OperationResult<CliStatus> = statusResult
    override suspend fun login(): OperationResult<Unit> = OperationResult(Unit)
    override suspend fun logout(): OperationResult<Unit> = OperationResult(Unit)
    override suspend fun history(): OperationResult<List<CliSession>> = historyResult
    override suspend fun settings(): OperationResult<CliSettings> = settingsResult
    override suspend fun isSetup(): OperationResult<Boolean> = setupResult

    override suspend fun setAutoLogin(enabled: Boolean): OperationResult<Unit> {
        autoLoginValue = enabled
        return OperationResult(Unit)
    }

    override suspend fun setAllowedSsids(values: Set<String>): OperationResult<Unit> {
        allowedSsidsValue = values
        return OperationResult(Unit)
    }

    override suspend fun setCredentials(userId: String, password: CharArray): OperationResult<Unit> {
        credentialUserId = userId
        credentialPassword = password.copyOf()
        return OperationResult(Unit)
    }

    override suspend fun runDaemon(): OperationResult<Unit> = OperationResult(Unit)

    override fun close() {
        closed = true
    }
}

private class FakeLifecycle : CliLifecycle {
    var activated = false
    var deactivated = false

    override suspend fun activate(): OperationResult<Unit> {
        activated = true
        return OperationResult(Unit)
    }

    override suspend fun deactivate(): OperationResult<Unit> {
        deactivated = true
        return OperationResult(Unit)
    }
}
