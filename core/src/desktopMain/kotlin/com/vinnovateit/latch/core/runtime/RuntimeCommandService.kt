package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val RUNTIME_COMMAND_TIMEOUT_MS = 20_000L

data class RuntimeSnapshot(val connection: String, val ssid: String?, val latched: Boolean)

@Serializable
data class RuntimeSessionRecord(
    val start: Long,
    val end: Long,
    val rx: Long,
    val tx: Long,
    val maxRx: Long,
    val maxTx: Long,
)

data class RuntimeSettingsSnapshot(val autoLogin: Boolean, val allowedSsids: Set<String>)

data class RuntimeOperation(
    val ok: Boolean,
    val code: String = if (ok) "OK" else "INTERNAL_ERROR",
    val message: String = "",
)

interface RuntimeCommandTarget {
    suspend fun isSetup(): Boolean
    suspend fun snapshot(): RuntimeSnapshot
    suspend fun login(): RuntimeOperation
    suspend fun logout(): RuntimeOperation
    suspend fun history(): List<RuntimeSessionRecord>
    suspend fun settings(): RuntimeSettingsSnapshot
    suspend fun setAutoLogin(enabled: Boolean)
    suspend fun setAllowedSsids(values: Set<String>)
    suspend fun setCredentials(userId: String, password: String)
}

class RuntimeCommandService(
    private val ownerKind: OwnerKind,
    private val target: RuntimeCommandTarget,
    private val onActivateUi: () -> Unit = {},
    private val onTakeOver: suspend () -> Boolean = { false },
    private val onDeactivate: suspend () -> Boolean = { false },
) {
    constructor(
        ownerKind: OwnerKind,
        runtime: DesktopEngineRuntime,
        onActivateUi: () -> Unit = {},
        onTakeOver: suspend () -> Boolean = { false },
        onDeactivate: suspend () -> Boolean = { false },
    ) : this(ownerKind, DesktopRuntimeTarget(runtime), onActivateUi, onTakeOver, onDeactivate)

    suspend fun execute(request: InstanceRequest): InstanceResponse {
        if (request.version != INSTANCE_PROTOCOL_VERSION) {
            return failure(request, "PROTOCOL_MISMATCH", "Unsupported protocol version.")
        }

        return try {
            when (request.command) {
                RuntimeCommand.PING -> success(request, mapOf("owner" to ownerKind.wireName()))
                RuntimeCommand.ACTIVATE_UI -> {
                    onActivateUi()
                    success(request)
                }
                RuntimeCommand.TAKE_OVER -> {
                    if (ownerKind == OwnerKind.CLI_DAEMON && onTakeOver()) success(request)
                    else failure(request, "OWNER_CHANGED", "The active owner refused takeover.")
                }
                RuntimeCommand.DEACTIVATE -> {
                    if (ownerKind == OwnerKind.CLI_DAEMON && onDeactivate()) success(request)
                    else failure(request, "OWNER_CHANGED", "The active owner is not the CLI daemon.")
                }
                RuntimeCommand.SETUP_STATUS -> success(
                    request,
                    mapOf("configured" to target.isSetup().toString()),
                )
                RuntimeCommand.STATUS -> status(request)
                RuntimeCommand.LOGIN -> operation(request, target.login())
                RuntimeCommand.LOGOUT -> operation(request, target.logout())
                RuntimeCommand.HISTORY -> success(
                    request,
                    mapOf("sessions" to JSON.encodeToString(target.history())),
                )
                RuntimeCommand.GET_SETTINGS -> {
                    val settings = target.settings()
                    success(
                        request,
                        mapOf(
                            "autoLogin" to settings.autoLogin.toString(),
                            "allowedSsids" to JSON.encodeToString(settings.allowedSsids.sorted()),
                        ),
                    )
                }
                RuntimeCommand.SET_SETTING -> setSetting(request)
                RuntimeCommand.SET_CREDENTIALS -> setCredentials(request)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(request, "INTERNAL_ERROR", "The owner could not complete the operation.")
        }
    }

    private suspend fun status(request: InstanceRequest): InstanceResponse {
        val snapshot = target.snapshot()
        return success(
            request,
            mapOf(
                "owner" to ownerKind.wireName(),
                "connection" to snapshot.connection,
                "ssid" to snapshot.ssid.orEmpty(),
                "latched" to snapshot.latched.toString(),
            ),
        )
    }

    private suspend fun setSetting(request: InstanceRequest): InstanceResponse {
        val key = request.arguments["key"]
            ?: return failure(request, "INVALID_ARGUMENT", "Setting key is required.")
        val value = request.arguments["value"]
            ?: return failure(request, "INVALID_ARGUMENT", "Setting value is required.")
        when (key) {
            "auto-login" -> {
                val enabled = when (value) {
                    "on" -> true
                    "off" -> false
                    else -> return failure(request, "INVALID_ARGUMENT", "auto-login must be on or off.")
                }
                target.setAutoLogin(enabled)
            }
            "allowed-ssids" -> {
                val values = value.split(',').map(String::trim)
                if (values.isEmpty() || values.any(String::isEmpty)) {
                    return failure(request, "INVALID_ARGUMENT", "allowed-ssids contains an empty entry.")
                }
                target.setAllowedSsids(values.toSet())
            }
            else -> return failure(request, "INVALID_ARGUMENT", "Unknown setting: $key")
        }
        return success(request)
    }

    private suspend fun setCredentials(request: InstanceRequest): InstanceResponse {
        val userId = request.arguments["userId"]?.trim().orEmpty()
        val password = request.arguments["password"].orEmpty()
        if (userId.isEmpty() || password.isEmpty()) {
            return failure(request, "INVALID_ARGUMENT", "Both user ID and password are required.")
        }
        target.setCredentials(userId, password)
        return success(request)
    }

    private fun operation(request: InstanceRequest, result: RuntimeOperation): InstanceResponse =
        if (result.ok) success(request) else failure(request, result.code, result.message)
}

private class DesktopRuntimeTarget(private val runtime: DesktopEngineRuntime) : RuntimeCommandTarget {
    override suspend fun isSetup(): Boolean = runtime.platform.credentials.exists()

    override suspend fun snapshot(): RuntimeSnapshot {
        val status = runtime.engine.status.value
        val connection = when (status) {
            ConnectionStatus.Idle -> if (runtime.platform.wifi.isConnectedToWifi()) "connected" else "disconnected"
            ConnectionStatus.Success -> "online"
            is ConnectionStatus.Connecting -> "connecting:${status.step.name.toKebabCase()}"
            is ConnectionStatus.Failed -> "failed:${status.reason.name.toKebabCase()}"
        }
        return RuntimeSnapshot(connection, runtime.platform.wifi.currentSsid(), runtime.engine.isLatched.value)
    }

    override suspend fun login(): RuntimeOperation = execute(LatchCommand.CheckAndLogin, "Login")

    override suspend fun logout(): RuntimeOperation = execute(LatchCommand.Logout, "Logout")

    override suspend fun history(): List<RuntimeSessionRecord> =
        runtime.database.statsDao().getAllSessions().first().map { session ->
            RuntimeSessionRecord(
                session.startTime,
                session.endTime,
                session.rxBytes,
                session.txBytes,
                session.maxRxBps,
                session.maxTxBps,
            )
        }

    override suspend fun settings() = RuntimeSettingsSnapshot(
        SettingsManager.autoLogin.value,
        SettingsManager.allowedSsids.value,
    )

    override suspend fun setAutoLogin(enabled: Boolean) = SettingsManager.setAutoLogin(enabled)

    override suspend fun setAllowedSsids(values: Set<String>) = SettingsManager.setAllowedSsids(values)

    override suspend fun setCredentials(userId: String, password: String) =
        runtime.platform.credentials.save(userId, password)

    private suspend fun execute(command: LatchCommand, label: String): RuntimeOperation {
        if (!runtime.engine.submitAndAwait(command, RUNTIME_COMMAND_TIMEOUT_MS)) {
            return RuntimeOperation(false, "TIMEOUT", "$label timed out.")
        }
        val failed = runtime.engine.status.value as? ConnectionStatus.Failed ?: return RuntimeOperation(true)
        val code = when (failed.reason) {
            ConnectionStatus.Reason.NoCredentials -> "NO_CREDENTIALS"
            ConnectionStatus.Reason.WifiOff,
            ConnectionStatus.Reason.NotOnWifi,
            ConnectionStatus.Reason.NotTargetNetwork,
            ConnectionStatus.Reason.Disconnected -> "NO_WIFI"
            else -> "INTERNAL_ERROR"
        }
        return RuntimeOperation(false, code, "$label failed: ${failed.reason.name.toKebabCase()}")
    }
}

private fun OwnerKind.wireName(): String = name.lowercase().replace('_', '-')

private fun String.toKebabCase(): String =
    fold(StringBuilder()) { result, character ->
        if (character.isUpperCase() && result.isNotEmpty()) result.append('-')
        result.append(character.lowercaseChar())
    }.toString()

private fun success(request: InstanceRequest, data: Map<String, String> = emptyMap()) =
    InstanceResponse(request.requestId, ok = true, code = "OK", data = data)

private fun failure(request: InstanceRequest, code: String, message: String) =
    InstanceResponse(request.requestId, ok = false, code = code, message = message)

private val JSON = Json { encodeDefaults = true }
