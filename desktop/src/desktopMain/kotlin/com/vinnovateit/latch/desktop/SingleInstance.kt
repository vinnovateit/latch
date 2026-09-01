package com.vinnovateit.latch.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * Strict single-instance guard.
 *
 * Ensures only ONE Latch instance can run concurrently. When a second (younger)
 * instance launches:
 *  1. Signals the older active instance to bring its window to front.
 *  2. Younger instance immediately self-terminates.
 *  3. Automatically recovers from stale locks or crashed previous instances.
 */
internal object SingleInstance {
    private var lock: FileLock? = null
    private var channelRef: FileChannel? = null
    private var serverSocket: ServerSocket? = null

    private val lockFile: File get() = AppPaths.dataDir.resolve(".lock")
    private val portFile: File get() = AppPaths.dataDir.resolve(".port")
    private val pidFile: File get() = AppPaths.dataDir.resolve(".pid")

    /**
     * @param onActivate Callback invoked on the running instance when a second instance tries to launch.
     * @return true if this process acquired the exclusive lock and is the sole instance.
     */
    fun acquire(onActivate: () -> Unit): Boolean {
        try {
            lockFile.parentFile?.mkdirs()

            // 1. Try file lock
            val channel = RandomAccessFile(lockFile, "rw").channel
            val acquired = runCatching { channel.tryLock() }.getOrNull()

            if (acquired != null) {
                lock = acquired
                channelRef = channel
                recordCurrentPid()
                startServer(onActivate)
                registerShutdownHook()
                return true
            }

            // Lock is held by another process -- try notifying it to show its window
            runCatching { channel.close() }
            val notified = notifyRunningInstance()

            if (notified) {
                // Older instance was notified and will show itself; younger instance exits
                return false
            }

            // Socket notification failed: check if the process holding lock is actually alive
            val existingPid = readExistingPid()
            val isAlive = existingPid != null && isProcessAlive(existingPid)

            if (!isAlive) {
                // Stale lock detected (process died abruptly): clean and retry once
                runCatching { lockFile.delete() }
                runCatching { portFile.delete() }
                runCatching { pidFile.delete() }

                val retryChannel = RandomAccessFile(lockFile, "rw").channel
                val retryLock = runCatching { retryChannel.tryLock() }.getOrNull()
                if (retryLock != null) {
                    lock = retryLock
                    channelRef = retryChannel
                    recordCurrentPid()
                    startServer(onActivate)
                    registerShutdownHook()
                    return true
                }
            }

            // Active instance exists; self-terminate
            return false
        } catch (_: Throwable) {
            val existingPid = readExistingPid()
            if (existingPid != null && isProcessAlive(existingPid)) {
                return false
            }
            return true
        }
    }

    private fun notifyRunningInstance(): Boolean {
        return try {
            if (!portFile.exists()) return false
            val port = portFile.readText().trim().toIntOrNull() ?: return false
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.soTimeout = 2000
                val out = socket.getOutputStream()
                out.write("SHOW\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun startServer(onActivate: () -> Unit) {
        runCatching {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            portFile.writeText(server.localPort.toString())

            thread(isDaemon = true, name = "SingleInstanceListener") {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        client.use {
                            val msg = it.getInputStream().bufferedReader().readLine()
                            if (msg == "SHOW") {
                                java.awt.EventQueue.invokeLater {
                                    onActivate()
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }
        }
    }

    private fun recordCurrentPid() {
        runCatching {
            val pid = ProcessHandle.current().pid()
            pidFile.writeText(pid.toString())
        }
    }

    private fun readExistingPid(): Long? {
        return runCatching {
            if (pidFile.exists()) pidFile.readText().trim().toLongOrNull() else null
        }.getOrNull()
    }

    private fun isProcessAlive(pid: Long): Boolean {
        return runCatching {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }.getOrDefault(false)
    }

    private fun registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { serverSocket?.close() }
            runCatching { lock?.release() }
            runCatching { channelRef?.close() }
            runCatching { portFile.delete() }
            runCatching { pidFile.delete() }
            runCatching { lockFile.delete() }
        })
    }
}
