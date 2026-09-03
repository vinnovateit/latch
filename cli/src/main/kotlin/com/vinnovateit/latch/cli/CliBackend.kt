package com.vinnovateit.latch.cli

data class CliStatus(
    val owner: String,
    val connection: String,
    val ssid: String?,
    val latched: Boolean,
)

data class CliSession(
    val start: Long,
    val end: Long,
    val rx: Long,
    val tx: Long,
    val maxRx: Long,
    val maxTx: Long,
)

data class CliSettings(
    val autoLogin: Boolean,
    val allowedSsids: Set<String>,
)

data class OperationResult<T>(
    val value: T? = null,
    val error: String? = null,
)

interface CliBackend : AutoCloseable {
    suspend fun isSetup(): OperationResult<Boolean>
    suspend fun status(): OperationResult<CliStatus>
    suspend fun login(): OperationResult<Unit>
    suspend fun logout(): OperationResult<Unit>
    suspend fun history(): OperationResult<List<CliSession>>
    suspend fun settings(): OperationResult<CliSettings>
    suspend fun setAutoLogin(enabled: Boolean): OperationResult<Unit>
    suspend fun setAllowedSsids(values: Set<String>): OperationResult<Unit>
    suspend fun setCredentials(userId: String, password: CharArray): OperationResult<Unit>
    suspend fun runDaemon(): OperationResult<Unit>
}

interface CliLifecycle {
    suspend fun activate(): OperationResult<Unit>
    suspend fun deactivate(): OperationResult<Unit>
}

internal object UnavailableCliLifecycle : CliLifecycle {
    override suspend fun activate() = OperationResult<Unit>(error = "Background lifecycle is unavailable.")
    override suspend fun deactivate() = OperationResult<Unit>(error = "Background lifecycle is unavailable.")
}

interface TerminalIO {
    val interactive: Boolean
    fun print(text: String)
    fun println(text: String = "")
    fun readLine(prompt: String): String?
    fun readSecret(prompt: String): CharArray?
}
