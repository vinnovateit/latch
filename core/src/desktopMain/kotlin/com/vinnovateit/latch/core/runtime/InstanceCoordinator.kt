package com.vinnovateit.latch.core.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val CONNECT_TIMEOUT_MS = 2_000
private const val REQUEST_READ_TIMEOUT_MS = 2_000
private const val RESPONSE_READ_TIMEOUT_MS = 25_000

/**
 * A login/logout request can run for tens of seconds. Servicing requests one
 * at a time on the accept loop meant a slow one queued every other request
 * behind it -- a STATUS call could time out despite the owner being healthy,
 * purely because it never got read off the socket. This bounds concurrent
 * request *handling*, not connections: `accept()` stays a tight loop, and
 * the engine remains the thing serializing its own mutating commands
 * (LatchEngine's command channel), so this is safe to widen.
 */
private const val MAX_CONCURRENT_REQUESTS = 8

/**
 * Why [InstanceCoordinator.tryAcquire] failed. [isTransitional] marks the
 * narrow, self-resolving windows worth a short bounded retry -- the lock is
 * held by a process that hasn't finished writing its metadata/token yet, or
 * by one that is dying -- as opposed to a definitive failure a retry cannot
 * fix (bad permissions, an incompatible peer, a real I/O error).
 */
enum class AcquisitionFailureReason(val isTransitional: Boolean) {
    IO_ERROR(isTransitional = false),
    OWNER_START_FAILED(isTransitional = false),
    INCOMPATIBLE_PROTOCOL(isTransitional = false),
    METADATA_UNAVAILABLE(isTransitional = true),
    TOKEN_UNAVAILABLE(isTransitional = true),
    OWNER_TRANSITIONING(isTransitional = true),
}

sealed interface AcquireResult {
    data class Owner(val coordinator: InstanceCoordinator) : AcquireResult
    data class Existing(val client: InstanceClient, val metadata: OwnerMetadata) : AcquireResult
    data class Failure(val message: String, val reason: AcquisitionFailureReason) : AcquireResult
}

class InstanceCoordinator private constructor(
    private val files: SecureRuntimeFiles,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val token: String,
    private val handler: suspend (InstanceRequest) -> InstanceResponse,
) : AutoCloseable {
    val port: Int get() = server.localPort
    private val closed = AtomicBoolean(false)
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_REQUESTS))
    private val listener = thread(start = false, isDaemon = true, name = "LatchRuntimeListener") {
        listen()
    }

    private fun start() {
        listener.start()
    }

    private fun listen() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            requestScope.launch { handle(socket) }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = REQUEST_READ_TIMEOUT_MS
            val response = try {
                when (val payload = readPayload(client)) {
                    is PayloadResult.TooLarge -> failure("", "PAYLOAD_TOO_LARGE", "Request exceeds 64 KiB.")
                    is PayloadResult.Value -> process(payload.text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure("", "MALFORMED_REQUEST", "Unable to read request.")
            }
            runCatching {
                client.getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(JSON.encodeToString(response))
                    writer.newLine()
                }
            }
        }
    }

    private suspend fun process(payload: String): InstanceResponse {
        val request = runCatching { JSON.decodeFromString<InstanceRequest>(payload) }.getOrNull()
            ?: return failure("", "MALFORMED_REQUEST", "Request is not valid protocol JSON.")
        if (request.version != INSTANCE_PROTOCOL_VERSION) {
            return failure(request.requestId, "PROTOCOL_MISMATCH", "Unsupported protocol version.")
        }
        if (!constantTimeEquals(token, request.token)) {
            return failure(request.requestId, "UNAUTHORIZED", "Authentication failed.")
        }
        if (request.requestId.isBlank()) {
            return failure("", "MALFORMED_REQUEST", "requestId is required.")
        }
        return try {
            handler(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(request.requestId, "INTERNAL_ERROR", "Owner could not process the request.")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        // Not joined: a handler may be blocked on the engine, and close() must
        // return promptly regardless. Each handler's own socket.use{} still
        // runs its close on the way out.
        requestScope.cancel()
        files.clearOwnerState()
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun tryAcquire(
            dataDir: File,
            ownerKind: OwnerKind,
            handler: suspend (InstanceRequest) -> InstanceResponse,
        ): AcquireResult {
            val files = SecureRuntimeFiles(dataDir)
            val channel = runCatching { RandomAccessFile(files.lockFile, "rw").channel }
                .getOrElse {
                    return AcquireResult.Failure("Unable to open runtime lock: ${it.message}", AcquisitionFailureReason.IO_ERROR)
                }
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                runCatching { channel.close() }
                return existingOwner(files)
            }

            return try {
                val token = files.createToken()
                val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                val metadata = OwnerMetadata(
                    version = INSTANCE_PROTOCOL_VERSION,
                    ownerKind = ownerKind,
                    port = server.localPort,
                    pid = ProcessHandle.current().pid(),
                    startedAt = System.currentTimeMillis(),
                )
                files.writeMetadata(metadata)
                val coordinator = InstanceCoordinator(files, channel, lock, server, token, handler)
                coordinator.start()
                AcquireResult.Owner(coordinator)
            } catch (error: Exception) {
                files.clearOwnerState()
                runCatching { lock.release() }
                runCatching { channel.close() }
                AcquireResult.Failure("Unable to start runtime owner: ${error.message}", AcquisitionFailureReason.OWNER_START_FAILED)
            }
        }

        private fun existingOwner(files: SecureRuntimeFiles): AcquireResult {
            val metadata = files.readMetadata() ?: return AcquireResult.Failure(
                "Runtime lock is held but owner metadata is unavailable.",
                AcquisitionFailureReason.METADATA_UNAVAILABLE,
            )
            val token = files.readToken() ?: return AcquireResult.Failure(
                "Runtime lock is held but authentication token is unavailable.",
                AcquisitionFailureReason.TOKEN_UNAVAILABLE,
            )
            if (metadata.version != INSTANCE_PROTOCOL_VERSION) {
                return AcquireResult.Failure(
                    "The running Latch instance uses an incompatible protocol.",
                    AcquisitionFailureReason.INCOMPATIBLE_PROTOCOL,
                )
            }
            val alive = runCatching {
                ProcessHandle.of(metadata.pid).map(ProcessHandle::isAlive).orElse(false)
            }.getOrDefault(false)
            if (!alive) return AcquireResult.Failure(
                "Runtime owner is no longer alive.",
                AcquisitionFailureReason.OWNER_TRANSITIONING,
            )
            return AcquireResult.Existing(InstanceClient(metadata.port, token), metadata)
        }
    }
}

class InstanceClient(
    private val port: Int,
    private val token: String,
) {
    suspend fun send(
        command: RuntimeCommand,
        arguments: Map<String, String> = emptyMap(),
    ): InstanceResponse {
        val request = InstanceRequest(
            version = INSTANCE_PROTOCOL_VERSION,
            token = token,
            requestId = UUID.randomUUID().toString(),
            command = command,
            arguments = arguments,
        )
        return sendRaw(JSON.encodeToString(request))
    }

    suspend fun sendRaw(payload: String): InstanceResponse = withContext(Dispatchers.IO) {
        val requestId = runCatching {
            JSON.decodeFromString<InstanceRequest>(payload).requestId
        }.getOrDefault("")
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = RESPONSE_READ_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(payload.toByteArray(Charsets.UTF_8))
                    write('\n'.code)
                    flush()
                }
                val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    ?: return@withContext failure(requestId, "OWNER_UNAVAILABLE", "Owner closed the connection.")
                JSON.decodeFromString<InstanceResponse>(line)
            }
        } catch (error: Exception) {
            failure(requestId, "OWNER_UNAVAILABLE", error.message ?: "Unable to contact owner.")
        }
    }
}

private sealed interface PayloadResult {
    data class Value(val text: String) : PayloadResult
    data object TooLarge : PayloadResult
}

private fun readPayload(socket: Socket): PayloadResult {
    val output = ByteArrayOutputStream()
    while (true) {
        val next = socket.getInputStream().read()
        if (next == -1 || next == '\n'.code) break
        if (output.size() >= MAX_INSTANCE_REQUEST_BYTES) return PayloadResult.TooLarge
        output.write(next)
    }
    return PayloadResult.Value(output.toString(Charsets.UTF_8))
}

private fun constantTimeEquals(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

private fun failure(requestId: String, code: String, message: String): InstanceResponse =
    InstanceResponse(requestId = requestId, ok = false, code = code, message = message)

private val JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
