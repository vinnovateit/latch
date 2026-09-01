package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.InstanceClient
import com.vinnovateit.latch.core.runtime.InstanceResponse
import com.vinnovateit.latch.core.runtime.RuntimeCommand
import com.vinnovateit.latch.core.runtime.RuntimeSessionRecord
import kotlinx.serialization.json.Json

class RemoteCliBackend(client: InstanceClient) : CliBackend by ProtocolCliBackend(client::send)

internal class ProtocolCliBackend(
    private val send: suspend (RuntimeCommand, Map<String, String>) -> InstanceResponse,
) : CliBackend {
    override suspend fun status(): OperationResult<CliStatus> {
        val response = send(RuntimeCommand.STATUS, emptyMap())
        response.errorOrNull()?.let { return OperationResult(error = it) }
        return OperationResult(
            CliStatus(
                owner = response.data["owner"].orEmpty(),
                connection = response.data["connection"].orEmpty(),
                ssid = response.data["ssid"]?.takeIf(String::isNotEmpty),
                latched = response.data["latched"]?.toBooleanStrictOrNull() ?: false,
            ),
        )
    }

    override suspend fun login(): OperationResult<Unit> =
        send(RuntimeCommand.LOGIN, emptyMap()).toUnitResult()

    override suspend fun logout(): OperationResult<Unit> =
        send(RuntimeCommand.LOGOUT, emptyMap()).toUnitResult()

    override suspend fun history(): OperationResult<List<CliSession>> {
        val response = send(RuntimeCommand.HISTORY, emptyMap())
        response.errorOrNull()?.let { return OperationResult(error = it) }
        val records = runCatching {
            JSON.decodeFromString<List<RuntimeSessionRecord>>(response.data.getValue("sessions"))
        }.getOrElse { return OperationResult(error = "The owner returned invalid session history.") }
        return OperationResult(
            records.map { CliSession(it.start, it.end, it.rx, it.tx, it.maxRx, it.maxTx) },
        )
    }

    override suspend fun settings(): OperationResult<CliSettings> {
        val response = send(RuntimeCommand.GET_SETTINGS, emptyMap())
        response.errorOrNull()?.let { return OperationResult(error = it) }
        val autoLogin = response.data["autoLogin"]?.toBooleanStrictOrNull()
            ?: return OperationResult(error = "The owner returned invalid settings.")
        val ssids = runCatching {
            JSON.decodeFromString<List<String>>(response.data.getValue("allowedSsids")).toSet()
        }.getOrElse { return OperationResult(error = "The owner returned invalid settings.") }
        return OperationResult(CliSettings(autoLogin, ssids))
    }

    override suspend fun setAutoLogin(enabled: Boolean): OperationResult<Unit> = send(
        RuntimeCommand.SET_SETTING,
        mapOf("key" to "auto-login", "value" to if (enabled) "on" else "off"),
    ).toUnitResult()

    override suspend fun setAllowedSsids(values: Set<String>): OperationResult<Unit> = send(
        RuntimeCommand.SET_SETTING,
        mapOf("key" to "allowed-ssids", "value" to values.joinToString(",")),
    ).toUnitResult()

    override suspend fun setCredentials(userId: String, password: CharArray): OperationResult<Unit> = send(
        RuntimeCommand.SET_CREDENTIALS,
        mapOf("userId" to userId, "password" to password.concatToString()),
    ).toUnitResult()

    override suspend fun runDaemon(): OperationResult<Unit> =
        OperationResult(error = "Latch is already running.")

    override fun close() = Unit
}

private fun InstanceResponse.toUnitResult(): OperationResult<Unit> =
    errorOrNull()?.let { OperationResult(error = it) } ?: OperationResult(Unit)

private fun InstanceResponse.errorOrNull(): String? =
    if (ok) null else message.ifBlank { code }

private val JSON = Json { ignoreUnknownKeys = false }
