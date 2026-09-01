package com.vinnovateit.latch.core.runtime

import kotlinx.serialization.Serializable

const val INSTANCE_PROTOCOL_VERSION = 1
const val MAX_INSTANCE_REQUEST_BYTES = 64 * 1024

@Serializable
enum class OwnerKind { DESKTOP, CLI_DAEMON, CLI_ONESHOT }

@Serializable
enum class RuntimeCommand {
    PING,
    ACTIVATE_UI,
    TAKE_OVER,
    STATUS,
    LOGIN,
    LOGOUT,
    HISTORY,
    GET_SETTINGS,
    SET_SETTING,
    SET_CREDENTIALS,
}

@Serializable
data class InstanceRequest(
    val version: Int,
    val token: String,
    val requestId: String,
    val command: RuntimeCommand,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class InstanceResponse(
    val requestId: String,
    val ok: Boolean,
    val code: String,
    val message: String = "",
    val data: Map<String, String> = emptyMap(),
)

@Serializable
data class OwnerMetadata(
    val version: Int,
    val ownerKind: OwnerKind,
    val port: Int,
    val pid: Long,
    val startedAt: Long,
)
