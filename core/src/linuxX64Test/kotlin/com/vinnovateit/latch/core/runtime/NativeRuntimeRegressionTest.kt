@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.JHttpConnection
import com.vinnovateit.latch.core.mp.JUrl
import com.vinnovateit.latch.core.mp.MPIOException
import com.vinnovateit.latch.core.mp.nanoTime
import com.vinnovateit.latch.core.mp.nowMillis
import com.vinnovateit.latch.desktop.platform.NativeHandle
import com.vinnovateit.latch.desktop.platform.parseNmcliTerseLine
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.AF_INET
import platform.posix.DT_DIR
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.closedir
import platform.posix.close
import platform.posix.getpid
import platform.posix.getsockname
import platform.posix.htons
import platform.posix.listen
import platform.posix.ntohs
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.stat
import platform.posix.unlink

private val testSeq = AtomicInt(0)

class NativeRuntimeRegressionTest {

    @Test
    fun `flock allows acquiring over stale metadata and rejects active second owner`() = runBlocking {
        withTempDir { dir ->
            val files = SecureRuntimeFiles(dir)
            // Simulate a crash or PID reuse leaving stale metadata/token on disk while unlocked.
            files.writeMetadata(
                OwnerMetadata(
                    version = INSTANCE_PROTOCOL_VERSION,
                    ownerKind = OwnerKind.DESKTOP,
                    port = 59999,
                    pid = getpid().toLong(),
                    startedAt = nowMillis() - 10_000,
                ),
            )
            files.writeToken("stale-token")

            val first = assertIs<AcquireResult.Owner>(
                InstanceCoordinator.tryAcquire(dir, OwnerKind.DESKTOP) { req ->
                    InstanceResponse(req.requestId, true, "OK", "activated")
                },
            )
            try {
                assertNotEquals("stale-token", files.readToken())
                val second = assertIs<AcquireResult.Existing>(
                    InstanceCoordinator.tryAcquire(dir, OwnerKind.DESKTOP) { req ->
                        InstanceResponse(req.requestId, true, "OK")
                    },
                )
                val response = second.client.send(RuntimeCommand.ACTIVATE_UI)
                assertTrue(response.ok)
                assertEquals("activated", response.message)
            } finally {
                val closeStartNs = nanoTime()
                first.coordinator.close()
                val closeMs = (nanoTime() - closeStartNs) / 1_000_000L
                assertTrue(closeMs < 2_000L, "coordinator.close() took ${closeMs}ms")
            }

            // Once closed, lock is released and another owner can acquire immediately.
            val reacquired = assertIs<AcquireResult.Owner>(
                InstanceCoordinator.tryAcquire(dir, OwnerKind.DESKTOP) { req ->
                    InstanceResponse(req.requestId, true, "OK")
                },
            )
            reacquired.coordinator.close()
        }
    }

    @Test
    fun `claimDesktopOwnership retries transient OWNER_UNAVAILABLE on ACTIVATE_UI`() = runBlocking {
        withTempDir { dir ->
            val calls = AtomicInt(0)
            val owner = assertIs<AcquireResult.Owner>(
                InstanceCoordinator.tryAcquire(dir, OwnerKind.DESKTOP) { req ->
                    val n = calls.addAndGet(1)
                    if (n == 1) {
                        InstanceResponse(req.requestId, false, "OWNER_UNAVAILABLE", "booting")
                    } else {
                        InstanceResponse(req.requestId, true, "OK")
                    }
                },
            )
            try {
                val claim = claimDesktopOwnership(dir, timeoutMillis = 1_000, retryDelayMillis = 10) {
                    InstanceResponse(it.requestId, true, "OK")
                }
                assertIs<DesktopOwnership.ActivatedExisting>(claim)
                assertEquals(2, calls.value)
            } finally {
                owner.coordinator.close()
            }
        }
    }

    @Test
    fun `coordinator worker pool handles concurrent requests cleanly`() = runBlocking {
        withTempDir { dir ->
            val handled = AtomicInt(0)
            val owner = assertIs<AcquireResult.Owner>(
                InstanceCoordinator.tryAcquire(dir, OwnerKind.DESKTOP) { req ->
                    delay(20)
                    handled.addAndGet(1)
                    InstanceResponse(req.requestId, true, "OK")
                },
            )
            try {
                val existing = assertIs<AcquireResult.Existing>(
                    InstanceCoordinator.tryAcquire(dir, OwnerKind.DESKTOP) {
                        InstanceResponse(it.requestId, true, "OK")
                    },
                )
                val responses = (1..12).map {
                    async(Dispatchers.Default) { existing.client.send(RuntimeCommand.STATUS) }
                }.awaitAll()
                assertTrue(responses.all { it.ok })
                assertEquals(12, handled.value)
            } finally {
                owner.coordinator.close()
            }
        }
    }

    @Test
    fun `secure runtime tokens are random hex with 0600 permissions`() {
        withTempDir { dir ->
            val files = SecureRuntimeFiles(dir)
            val first = files.createToken()
            val second = files.createToken()
            assertEquals(64, first.length)
            assertEquals(64, second.length)
            assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
            assertNotEquals(first, second)

            memScoped {
                val st = alloc<stat>()
                assertEquals(0, stat(files.tokenFile.path, st.ptr))
                assertEquals(0x180, st.st_mode.toInt() and 0x1FF)
            }
        }
    }

    @Test
    fun `JFile joins string child paths and mkdirs creates nested directories recursively`() {
        withTempDir { dir ->
            val autostartDir = JFile(dir.path, ".config/autostart")
            assertEquals("${dir.path}/.config/autostart", autostartDir.path)
            assertTrue(autostartDir.mkdirs())
            assertTrue(autostartDir.isDirectory)
            assertTrue(autostartDir.mkdirs())

            val desktopFile = JFile(dir.path, ".config/autostart/latch.desktop")
            assertEquals("${dir.path}/.config/autostart/latch.desktop", desktopFile.path)
            assertEquals(desktopFile.path, JFile(autostartDir, "latch.desktop").path)
            assertTrue(!desktopFile.exists())
        }
    }

    @Test
    fun `NativeHandle uses structural equality and parseNmcliTerseLine unescapes colons`() {
        assertEquals(NativeHandle("wlp2s0"), NativeHandle("wlp2s0"))
        assertEquals(NativeHandle("wlp2s0").hashCode(), NativeHandle("wlp2s0").hashCode())
        assertEquals(
            listOf("yes", "wlp2s0", "76", "My:Hostel\\5G"),
            parseNmcliTerseLine("""yes:wlp2s0:76:My\:Hostel\\5G"""),
        )
    }

    @Test
    fun `JHttpConnection disconnect aborts stalled response promptly`() = runBlocking {
        withStalledLoopbackServer { port ->
            val conn = JHttpConnection(JUrl("http://127.0.0.1:$port/generate_204")).apply {
                connectTimeout = 2_000
                readTimeout = 5_000
            }
            val startNs = nanoTime()
            val killer = launch(Dispatchers.Default) {
                delay(80)
                conn.disconnect()
            }
            assertFailsWith<MPIOException> {
                conn.responseCode
            }
            killer.join()
            val elapsedMs = (nanoTime() - startNs) / 1_000_000L
            assertTrue(elapsedMs < 2_000L, "disconnect() took ${elapsedMs}ms instead of aborting promptly")
        }
    }

    @Test
    fun `JHttpConnection enforces bounded wall-clock deadline on stalled server`() {
        withStalledLoopbackServer { port ->
            val conn = JHttpConnection(JUrl("http://127.0.0.1:$port/generate_204")).apply {
                connectTimeout = 500
                readTimeout = 150
            }
            val startNs = nanoTime()
            assertFailsWith<MPIOException> {
                conn.responseCode
            }
            val elapsedMs = (nanoTime() - startNs) / 1_000_000L
            assertTrue(elapsedMs < 2_000L, "readTimeout took ${elapsedMs}ms")
        }
    }

    private inline fun withStalledLoopbackServer(block: (port: Int) -> Unit) {
        val serverFd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(serverFd >= 0, "socket() failed")
        try {
            memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.toUShort()
                addr.sin_port = htons(0u).toUShort()
                addr.sin_addr.s_addr = 0x0100007Fu
                assertEquals(0, bind(serverFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()))
                assertEquals(0, listen(serverFd, 4))
                val len = alloc<UIntVar>().apply { value = sizeOf<sockaddr_in>().toUInt() }
                assertEquals(0, getsockname(serverFd, addr.ptr.reinterpret(), len.ptr))
                val port = ntohs(addr.sin_port).toInt()
                block(port)
            }
        } finally {
            close(serverFd)
        }
    }

    private inline fun withTempDir(block: (JFile) -> Unit) {
        val dir = JFile("/tmp/latch-native-test-${getpid()}-${testSeq.addAndGet(1)}-${nowMillis()}")
        assertTrue(dir.mkdirs())
        try {
            block(dir)
        } finally {
            deleteRecursively(dir.path)
        }
    }

    private fun deleteRecursively(path: String) {
        val dp = opendir(path)
        if (dp != null) {
            try {
                while (true) {
                    val entry = readdir(dp) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name == "." || name == "..") continue
                    val child = "$path/$name"
                    if (entry.pointed.d_type.toInt() == DT_DIR) {
                        deleteRecursively(child)
                    } else {
                        unlink(child)
                    }
                }
            } finally {
                closedir(dp)
            }
            rmdir(path)
        } else {
            unlink(path)
        }
    }
}
