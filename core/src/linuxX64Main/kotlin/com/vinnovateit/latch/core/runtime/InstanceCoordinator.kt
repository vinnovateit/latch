@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.ioDispatcher
import com.vinnovateit.latch.core.mp.nanoTime
import com.vinnovateit.latch.core.mp.nowMillis
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.linux.flock
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EPERM
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_CREAT
import platform.posix.O_NONBLOCK
import platform.posix.O_RDWR
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fopen
import platform.posix.fclose
import platform.posix.getpid
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.htons
import platform.posix.kill
import platform.posix.listen
import platform.posix.ntohs
import platform.posix.open
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket

private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }

private const val LISTEN_BACKLOG = 50
private const val CONNECT_TIMEOUT_MS = 2_000
private const val REQUEST_READ_TIMEOUT_MS = 2_000
private const val RESPONSE_READ_TIMEOUT_MS = 25_000

internal const val MAX_CONCURRENT_REQUESTS = 8
internal const val MAX_PENDING_REQUESTS = 16

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
    private val lockFd: Int,
    private val serverFd: Int,
    val port: Int,
    private val token: String,
    private val handler: suspend (InstanceRequest) -> InstanceResponse,
) : AutoCloseable {
    private val closed = AtomicInt(0)
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val listenerDispatcher = newSingleThreadContext("latch-ipc-listener")
    @OptIn(DelicateCoroutinesApi::class)
    private val requestDispatcher = newFixedThreadPoolContext(MAX_CONCURRENT_REQUESTS, "latch-ipc-worker")
    private val listenerScope = CoroutineScope(SupervisorJob() + listenerDispatcher)
    private val requestScope = CoroutineScope(SupervisorJob() + requestDispatcher)
    private val pending = Channel<Int>(capacity = MAX_PENDING_REQUESTS)

    private fun start() {
        repeat(MAX_CONCURRENT_REQUESTS) {
            requestScope.launch {
                for (clientFd in pending) handle(clientFd)
            }
        }
        listenerScope.launch { listen() }
    }

    private fun listen() {
        while (closed.value == 0) {
            val client = accept(serverFd, null, null)
            if (client < 0) {
                if (closed.value != 0) break
                if (errno == EINTR) continue
                break
            }
            if (!pending.trySend(client).isSuccess) {
                close(client)
            }
        }
    }

    private suspend fun handle(clientFd: Int) {
        try {
            val response = try {
                when (val payload = readPayload(clientFd, REQUEST_READ_TIMEOUT_MS)) {
                    is PayloadResult.TooLarge -> failure("", "PAYLOAD_TOO_LARGE", "Request exceeds 64 KiB.")
                    is PayloadResult.Timeout -> failure("", "MALFORMED_REQUEST", "Request timed out.")
                    is PayloadResult.Value -> process(payload.text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                failure("", "MALFORMED_REQUEST", "Unable to read request.")
            }
            val bytes = (JSON.encodeToString(response) + "\n").encodeToByteArray()
            runCatching { sendAll(clientFd, bytes, REQUEST_READ_TIMEOUT_MS) }
        } finally {
            close(clientFd)
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
        } catch (_: Throwable) {
            failure(request.requestId, "INTERNAL_ERROR", "Owner could not process the request.")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(0, 1)) return
        shutdown(serverFd, SHUT_RDWR)
        close(serverFd)
        pending.close()
        while (true) {
            val leftover = pending.tryReceive().getOrNull() ?: break
            close(leftover)
        }
        requestScope.cancel()
        listenerScope.cancel()
        runCatching { listenerDispatcher.close() }
        runCatching { requestDispatcher.close() }
        files.clearOwnerState()
        flock(lockFd, LOCK_UN)
        close(lockFd)
    }

    companion object {
        fun tryAcquire(
            dataDir: JFile,
            ownerKind: OwnerKind,
            handler: suspend (InstanceRequest) -> InstanceResponse,
        ): AcquireResult {
            val files = SecureRuntimeFiles(dataDir)
            val lockFd = open(files.lockFile.path, O_CREAT or O_RDWR, 0x180u)
            if (lockFd < 0) {
                return AcquireResult.Failure(
                    "Unable to open runtime lock (errno=$errno).",
                    AcquisitionFailureReason.IO_ERROR,
                )
            }
            if (flock(lockFd, LOCK_EX or LOCK_NB) != 0) {
                val err = errno
                close(lockFd)
                return if (err == EWOULDBLOCK || err == EAGAIN) {
                    existingOwner(files)
                } else {
                    AcquireResult.Failure(
                        "Unable to acquire runtime lock (errno=$err).",
                        AcquisitionFailureReason.IO_ERROR,
                    )
                }
            }

            var fd = -1
            return try {
                val token = files.createToken()
                fd = socket(AF_INET, SOCK_STREAM, 0)
                if (fd < 0) error("socket failed (errno=$errno)")
                memScoped {
                    val address = alloc<sockaddr_in>()
                    address.sin_family = AF_INET.toUShort()
                    address.sin_port = htons(0u).toUShort()
                    address.sin_addr.s_addr = 0x0100007Fu
                    if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) != 0) {
                        error("bind failed (errno=$errno)")
                    }
                }
                if (listen(fd, LISTEN_BACKLOG) != 0) error("listen failed (errno=$errno)")
                val port = memScoped {
                    val address = alloc<sockaddr_in>()
                    val length = alloc<UIntVar>()
                    length.value = sizeOf<sockaddr_in>().toUInt()
                    if (getsockname(fd, address.ptr.reinterpret(), length.ptr) != 0) {
                        error("getsockname failed (errno=$errno)")
                    }
                    ntohs(address.sin_port).toInt()
                }
                files.writeMetadata(
                    OwnerMetadata(
                        version = INSTANCE_PROTOCOL_VERSION,
                        ownerKind = ownerKind,
                        port = port,
                        pid = getpid().toLong(),
                        startedAt = nowMillis(),
                    ),
                )
                val coordinator = InstanceCoordinator(files, lockFd, fd, port, token, handler)
                coordinator.start()
                AcquireResult.Owner(coordinator)
            } catch (error: Throwable) {
                if (fd >= 0) close(fd)
                files.clearOwnerState()
                flock(lockFd, LOCK_UN)
                close(lockFd)
                AcquireResult.Failure(
                    "Unable to start runtime owner: ${error.message}",
                    AcquisitionFailureReason.OWNER_START_FAILED,
                )
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
            if (!processAlive(metadata.pid)) {
                return AcquireResult.Failure(
                    "Runtime owner is no longer alive.",
                    AcquisitionFailureReason.OWNER_TRANSITIONING,
                )
            }
            return AcquireResult.Existing(InstanceClient(metadata.port, token), metadata)
        }
    }
}

private fun processAlive(pid: Long): Boolean {
    if (pid <= 0 || pid > Int.MAX_VALUE) return false
    if (kill(pid.toInt(), 0) == 0 || errno == EPERM) {
        val handle = fopen("/proc/$pid/stat", "r") ?: return false
        fclose(handle)
        return true
    }
    return false
}

private sealed interface PayloadResult {
    data class Value(val text: String) : PayloadResult
    data object TooLarge : PayloadResult
    data object Timeout : PayloadResult
}

private fun readPayload(fd: Int, timeoutMs: Int): PayloadResult {
    val deadlineNs = nanoTime() + timeoutMs.toLong() * 1_000_000L
    val out = ArrayList<Byte>(256)
    val buffer = ByteArray(4 * 1024)
    while (true) {
        val remainingMs = ((deadlineNs - nanoTime()) / 1_000_000L).toInt()
        if (remainingMs <= 0) return PayloadResult.Timeout
        if (!waitSocket(fd, POLLIN.toShort(), remainingMs)) return PayloadResult.Timeout
        val n = recv(fd, buffer.refTo(0), buffer.size.toULong(), 0).toInt()
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue
            return if (out.isEmpty()) PayloadResult.Timeout else PayloadResult.Value(out.toByteArray().decodeToString())
        }
        if (n == 0) {
            return if (out.isEmpty()) PayloadResult.Timeout else PayloadResult.Value(out.toByteArray().decodeToString())
        }
        val newline = (0 until n).firstOrNull { buffer[it] == '\n'.code.toByte() }
        val take = newline ?: n
        if (out.size + take > MAX_INSTANCE_REQUEST_BYTES) return PayloadResult.TooLarge
        for (i in 0 until take) out.add(buffer[i])
        if (newline != null) return PayloadResult.Value(out.toByteArray().decodeToString())
    }
}

private fun waitSocket(fd: Int, events: Short, timeoutMs: Int): Boolean = memScoped {
    val pfd = allocArray<pollfd>(1)
    pfd[0].fd = fd
    pfd[0].events = events
    pfd[0].revents = 0
    var ready = false
    while (true) {
        val rc = poll(pfd, 1u, timeoutMs)
        if (rc > 0) {
            ready = true
            break
        }
        if (rc == 0 || errno != EINTR) break
    }
    ready
}

private fun sendAll(fd: Int, bytes: ByteArray, timeoutMs: Int) {
    val deadlineNs = nanoTime() + timeoutMs.toLong() * 1_000_000L
    var offset = 0
    bytes.usePinned { pinned ->
        while (offset < bytes.size) {
            val remainingMs = ((deadlineNs - nanoTime()) / 1_000_000L).toInt()
            if (remainingMs <= 0) error("Send timed out")
            if (!waitSocket(fd, POLLOUT.toShort(), remainingMs)) error("Send timed out")
            val sent = send(fd, pinned.addressOf(offset), (bytes.size - offset).toULong(), MSG_NOSIGNAL).toInt()
            if (sent < 0) {
                if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue
                error("Send failed (errno=$errno)")
            }
            if (sent == 0) error("Connection closed while sending")
            offset += sent
        }
    }
}

private fun connectWithTimeout(fd: Int, port: Int, timeoutMs: Int): Boolean {
    val oldFlags = fcntl(fd, F_GETFL, 0)
    if (oldFlags < 0) return false
    if (fcntl(fd, F_SETFL, oldFlags or O_NONBLOCK) != 0) return false
    return try {
        val rc = memScoped {
            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.toUShort()
            address.sin_port = htons(port.toUShort()).toUShort()
            address.sin_addr.s_addr = 0x0100007Fu
            connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
        }
        if (rc == 0) return true
        if (errno != EINPROGRESS) return false
        if (!waitSocket(fd, POLLOUT.toShort(), timeoutMs)) return false
        memScoped {
            val soError = alloc<IntVar>()
            val len = alloc<UIntVar>()
            len.value = sizeOf<IntVar>().toUInt()
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, soError.ptr, len.ptr) != 0) return false
            soError.value == 0
        }
    } finally {
        fcntl(fd, F_SETFL, oldFlags)
    }
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

    suspend fun sendRaw(payload: String): InstanceResponse = withContext(ioDispatcher) {
        val requestId = runCatching { JSON.decodeFromString<InstanceRequest>(payload).requestId }.getOrDefault("")
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return@withContext failure(requestId, "OWNER_UNAVAILABLE", "Unable to open socket.")
        try {
            if (!connectWithTimeout(fd, port, CONNECT_TIMEOUT_MS)) {
                return@withContext failure(requestId, "OWNER_UNAVAILABLE", "Owner is not listening.")
            }
            val bytes = (payload + "\n").encodeToByteArray()
            sendAll(fd, bytes, CONNECT_TIMEOUT_MS)
            when (val reply = readPayload(fd, RESPONSE_READ_TIMEOUT_MS)) {
                is PayloadResult.Value -> JSON.decodeFromString<InstanceResponse>(reply.text)
                is PayloadResult.TooLarge -> failure(requestId, "OWNER_UNAVAILABLE", "Owner response exceeded limit.")
                is PayloadResult.Timeout -> failure(requestId, "OWNER_UNAVAILABLE", "Owner closed the connection.")
            }
        } catch (error: Throwable) {
            failure(requestId, "OWNER_UNAVAILABLE", error.message ?: "Unable to contact owner.")
        } finally {
            close(fd)
        }
    }
}

private fun constantTimeEquals(expected: String, actual: String): Boolean {
    var diff = expected.length xor actual.length
    val max = minOf(expected.length, actual.length)
    for (i in 0 until max) {
        diff = diff or (expected[i].code xor actual[i].code)
    }
    return diff == 0
}

private fun failure(requestId: String, code: String, message: String): InstanceResponse =
    InstanceResponse(requestId = requestId, ok = false, code = code, message = message)

private fun randomId(): String {
    val bytes = secureRandomBytes(16)
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
