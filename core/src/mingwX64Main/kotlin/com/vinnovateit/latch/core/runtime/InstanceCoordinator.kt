@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.mp.JFile
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import platform.posix.INVALID_SOCKET
import platform.posix.SOCKET
import platform.posix.getpid
import platform.posix.rand
import platform.posix.sockaddr_in
import platform.windows.AF_INET
import platform.windows.SOCK_STREAM
import platform.windows.accept
import platform.windows.bind
import platform.windows.closesocket
import platform.windows.connect
import platform.windows.getsockname
import platform.windows.htons
import platform.windows.listen
import platform.windows.ntohs
import platform.windows.recv
import platform.windows.send
import platform.windows.socket

/** Winsock must be initialised before any socket() call on Windows. */
private val winsockReady: Boolean = kotlinx.cinterop.memScoped {
    val data = alloc<platform.posix.WSADATA>()
    platform.windows.WSAStartup(0x0202u, data.ptr) == 0
}

private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }

private const val LISTEN_BACKLOG = 50
private const val RESPONSE_BUFFER = 64 * 1024

enum class AcquisitionFailureReason(val isTransitional: Boolean) {
    IO_ERROR(false),
    OWNER_START_FAILED(false),
    INCOMPATIBLE_PROTOCOL(false),
    METADATA_UNAVAILABLE(true),
    TOKEN_UNAVAILABLE(true),
    OWNER_TRANSITIONING(true),
}

sealed interface AcquireResult {
    data class Owner(val coordinator: InstanceCoordinator) : AcquireResult
    data class Existing(val client: InstanceClient, val metadata: OwnerMetadata) : AcquireResult
    data class Failure(val message: String, val reason: AcquisitionFailureReason) : AcquireResult
}

class InstanceCoordinator private constructor(
    private val files: SecureRuntimeFiles,
    private val serverFd: SOCKET,
    val port: Int,
    private val token: String,
    private val handler: suspend (InstanceRequest) -> InstanceResponse,
) : AutoCloseable {
    @kotlin.concurrent.Volatile private var closed = false
    private val listenerScope = CoroutineScope(Dispatchers.Default)

    private fun start() { listenerScope.launch { listen() } }

    private fun listen() {
        while (!closed) {
            // Winsock requires both addr and addrlen to be null here; passing a
            // real addrlen with a null addr fails with WSAEFAULT and kills the
            // listener, leaving a second instance waiting forever for a reply.
            val client = accept(serverFd, null, null)
            if (client == INVALID_SOCKET) break
            try {
                val requestLine = readLine(client) ?: continue
                val request = runCatching { JSON.decodeFromString<InstanceRequest>(requestLine) }.getOrNull()
                val response = if (request == null) {
                    InstanceResponse("", false, "MALFORMED_REQUEST", "Request is not valid protocol JSON.")
                } else {
                    process(request)
                }
                val bytes = (JSON.encodeToString(response) + "\n").encodeToByteArray()
                send(client, bytes.refTo(0), bytes.size, 0)
            } catch (_: Throwable) {
            } finally {
                closesocket(client)
            }
        }
    }

    private fun process(request: InstanceRequest): InstanceResponse {
        if (request.version != INSTANCE_PROTOCOL_VERSION) {
            return InstanceResponse(request.requestId, false, "PROTOCOL_MISMATCH", "Unsupported protocol version.")
        }
        if (request.token != token) {
            return InstanceResponse(request.requestId, false, "UNAUTHORIZED", "Authentication failed.")
        }
        return runCatching { kotlinx.coroutines.runBlocking { handler(request) } }
            .getOrElse { InstanceResponse(request.requestId, false, "INTERNAL_ERROR", "Owner could not process the request.") }
    }

    override fun close() {
        if (closed) return
        closed = true
        closesocket(serverFd)
        files.clearOwnerState()
    }

    companion object {
        fun tryAcquire(
            dataDir: JFile,
            ownerKind: OwnerKind,
            handler: suspend (InstanceRequest) -> InstanceResponse,
        ): AcquireResult {
            check(winsockReady) { "Winsock initialisation failed" }
            val files = SecureRuntimeFiles(dataDir)
            val existing = existingOwner(files)
            if (existing is AcquireResult.Existing) return existing
            if (existing is AcquireResult.Failure && !existing.reason.isTransitional) return existing

            return try {
                val fd = socket(AF_INET, SOCK_STREAM, 0)
                if (fd == INVALID_SOCKET) error("socket failed")
                memScoped {
                    val address = alloc<sockaddr_in>()
                    address.sin_family = AF_INET.toShort()
                    address.sin_port = htons(0u)
                    address.sin_addr.S_un.S_addr = 0x0100007Fu
                    if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toInt()) != 0) error("bind failed")
                }
                if (listen(fd, LISTEN_BACKLOG) != 0) error("listen failed")
                val port = memScoped {
                    val address = alloc<sockaddr_in>()
                    val length = alloc<IntVar>()
                    length.value = sizeOf<sockaddr_in>().toInt()
                    getsockname(fd, address.ptr.reinterpret(), length.ptr)
                    ntohs(address.sin_port).toInt()
                }
                val token = files.createToken()
                files.writeMetadata(
                    OwnerMetadata(
                        version = INSTANCE_PROTOCOL_VERSION,
                        ownerKind = ownerKind,
                        port = port,
                        pid = getpid().toLong(),
                        startedAt = com.vinnovateit.latch.core.mp.nowMillis(),
                    ),
                )
                val coordinator = InstanceCoordinator(files, fd, port, token, handler)
                coordinator.start()
                AcquireResult.Owner(coordinator)
            } catch (error: Throwable) {
                files.clearOwnerState()
                AcquireResult.Failure("Unable to start runtime owner: ${error.message}", AcquisitionFailureReason.OWNER_START_FAILED)
            }
        }

        private fun existingOwner(files: SecureRuntimeFiles): AcquireResult {
            val metadata = files.readMetadata() ?: return AcquireResult.Failure(
                "Runtime owner metadata is unavailable.", AcquisitionFailureReason.METADATA_UNAVAILABLE,
            )
            val token = files.readToken() ?: return AcquireResult.Failure(
                "Runtime token is unavailable.", AcquisitionFailureReason.TOKEN_UNAVAILABLE,
            )
            if (metadata.version != INSTANCE_PROTOCOL_VERSION) {
                return AcquireResult.Failure("Incompatible protocol.", AcquisitionFailureReason.INCOMPATIBLE_PROTOCOL)
            }
            if (!processAlive(metadata.pid)) {
                return AcquireResult.Failure("Runtime owner is no longer alive.", AcquisitionFailureReason.OWNER_TRANSITIONING)
            }
            return AcquireResult.Existing(InstanceClient(metadata.port, token), metadata)
        }
    }
}

private fun processAlive(pid: Long): Boolean {
    if (pid <= 0) return false
    val output = com.vinnovateit.latch.desktop.platform.runCommand(
        "tasklist /FI \"PID eq $pid\" /NH",
    )
    return output.contains(pid.toString())
}

private fun readLine(fd: SOCKET): String? {
    val out = StringBuilder()
    val byte = ByteArray(1)
    while (out.length < MAX_INSTANCE_REQUEST_BYTES) {
        val n = recv(fd, byte.refTo(0), 1, 0)
        if (n <= 0) return if (out.isEmpty()) null else out.toString()
        val c = byte[0].toInt().toChar()
        if (c == '\n') return out.toString()
        out.append(c)
    }
    return null
}

class InstanceClient(private val port: Int, private val token: String) {
    suspend fun send(
        command: RuntimeCommand,
        arguments: Map<String, String> = emptyMap(),
    ): InstanceResponse {
        val request = InstanceRequest(
            version = INSTANCE_PROTOCOL_VERSION,
            token = token,
            requestId = randomId(),
            command = command,
            arguments = arguments,
        )
        return sendRaw(JSON.encodeToString(request))
    }

    suspend fun sendRaw(payload: String): InstanceResponse {
        if (!winsockReady) return InstanceResponse("", false, "OWNER_UNAVAILABLE", "Winsock unavailable.")
        val requestId = runCatching { JSON.decodeFromString<InstanceRequest>(payload).requestId }.getOrDefault("")
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd == INVALID_SOCKET) {
            return InstanceResponse(requestId, false, "OWNER_UNAVAILABLE", "Unable to open socket.")
        }
        return try {
            memScoped {
                val address = alloc<sockaddr_in>()
                address.sin_family = AF_INET.toShort()
                address.sin_port = htons(port.toUShort())
                address.sin_addr.S_un.S_addr = 0x0100007Fu
                if (connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toInt()) != 0) {
                    return@memScoped InstanceResponse(requestId, false, "OWNER_UNAVAILABLE", "Owner is not listening.")
                }
            }
            val bytes = (payload + "\n").encodeToByteArray()
            send(fd, bytes.refTo(0), bytes.size, 0)
            val line = readLine(fd)
                ?: return InstanceResponse(requestId, false, "OWNER_UNAVAILABLE", "Owner closed the connection.")
            JSON.decodeFromString<InstanceResponse>(line)
        } catch (error: Throwable) {
            InstanceResponse(requestId, false, "OWNER_UNAVAILABLE", error.message ?: "Unable to contact owner.")
        } finally {
            closesocket(fd)
        }
    }
}

private fun randomId(): String {
    val bytes = ByteArray(16) { rand().toByte() }
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
