@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop.platform

import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.O_WRONLY
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.getpid
import platform.posix.kill
import platform.posix.open
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.usleep
import platform.posix.waitpid
import platform.posix.write

/**
 * Watches for a click on the AppIndicator icon itself.
 *
 * SDL 3.4 has no tray-icon click callback, but a click sends the
 * `org.kde.StatusNotifierItem.Activate` method call over the session bus.
 * This is a best-effort bridge: if there is no session bus or no monitor
 * tool, the menu entries still work.
 */
internal class TrayIconActivation(private val onActivate: () -> Unit) {
    private val started = AtomicInt(0)
    private val stopRequested = AtomicInt(0)
    private val wakeWriteFd = AtomicInt(-1)
    private val childPid = AtomicInt(-1)
    internal val activeChildPid: Int get() = childPid.value
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun start() {
        if (!started.compareAndSet(0, 1)) return
        if (platform.posix.getenv("DBUS_SESSION_BUS_ADDRESS") == null) {
            started.value = 0
            return
        }
        stopRequested.value = 0
        val ownPath = "sdl_appindicator_${getpid()}_"
        val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = workerScope
        job = workerScope.launch {
            var stdoutRead = -1
            var wakeRead = -1
            var wakeWrite = -1
            try {
                val fds = memScoped {
                    val stdoutPipe = allocArray<IntVar>(2)
                    if (pipe(stdoutPipe) != 0) return@memScoped null
                    val wakePipe = allocArray<IntVar>(2)
                    if (pipe(wakePipe) != 0) {
                        close(stdoutPipe[0])
                        close(stdoutPipe[1])
                        return@memScoped null
                    }
                    val prog = "dbus-monitor".cstr.getPointer(this)
                    val arg1 = "--session".cstr.getPointer(this)
                    val arg2 = "interface='org.kde.StatusNotifierItem'".cstr.getPointer(this)
                    val argv = allocArray<CPointerVar<ByteVar>>(4)
                    argv[0] = prog
                    argv[1] = arg1
                    argv[2] = arg2
                    argv[3] = null

                    val pid = fork()
                    if (pid == 0) {
                        close(stdoutPipe[0])
                        close(wakePipe[0])
                        close(wakePipe[1])
                        dup2(stdoutPipe[1], STDOUT_FILENO)
                        close(stdoutPipe[1])
                        val devNull = open("/dev/null", O_WRONLY, 0)
                        if (devNull >= 0) {
                            dup2(devNull, STDERR_FILENO)
                            close(devNull)
                        }
                        execvp(prog, argv)
                        _exit(127)
                    }
                    close(stdoutPipe[1])
                    if (pid < 0) {
                        close(stdoutPipe[0])
                        close(wakePipe[0])
                        close(wakePipe[1])
                        return@memScoped null
                    }
                    Triple(pid, stdoutPipe[0], wakePipe[0] to wakePipe[1])
                } ?: return@launch

                val pid = fds.first
                stdoutRead = fds.second
                wakeRead = fds.third.first
                wakeWrite = fds.third.second
                setNonBlocking(stdoutRead)
                setNonBlocking(wakeRead)
                wakeWriteFd.value = wakeWrite
                childPid.value = pid

                val buffer = ByteArray(1024)
                var pending = ""
                while (isActive && stopRequested.value == 0) {
                    val ready = memScoped {
                        val pfd = allocArray<pollfd>(2)
                        pfd[0].fd = stdoutRead
                        pfd[0].events = POLLIN.toShort()
                        pfd[0].revents = 0
                        pfd[1].fd = wakeRead
                        pfd[1].events = POLLIN.toShort()
                        pfd[1].revents = 0
                        val rc = poll(pfd, 2u, 1000)
                        if (rc < 0) {
                            if (errno == EINTR) 0 else -1
                        } else {
                            val wakeEvents = pfd[1].revents.toInt()
                            val outEvents = pfd[0].revents.toInt()
                            when {
                                (wakeEvents and (POLLIN or POLLHUP or POLLERR)) != 0 -> -1
                                (outEvents and POLLIN) != 0 -> 1
                                (outEvents and (POLLHUP or POLLERR)) != 0 -> -1
                                else -> 0
                            }
                        }
                    }
                    if (ready < 0) break
                    if (ready == 0) continue

                    var eof = false
                    while (isActive && stopRequested.value == 0) {
                        val n = read(stdoutRead, buffer.refTo(0), buffer.size.toULong()).toInt()
                        if (n > 0) {
                            pending += buffer.decodeToString(0, n)
                            var newline = pending.indexOf('\n')
                            while (newline >= 0) {
                                val line = pending.substring(0, newline)
                                pending = pending.substring(newline + 1)
                                if (line.contains("interface=org.kde.StatusNotifierItem") &&
                                    (line.contains("member=Activate") ||
                                        line.contains("member=SecondaryActivate")) &&
                                    line.contains(ownPath)
                                ) {
                                    onActivate()
                                }
                                newline = pending.indexOf('\n')
                            }
                        } else if (n == 0) {
                            eof = true
                            break
                        } else {
                            val err = errno
                            if (err == EINTR) continue
                            if (err == EAGAIN || err == EWOULDBLOCK) break
                            eof = true
                            break
                        }
                    }
                    if (eof) break
                }
            } finally {
                wakeWriteFd.value = -1
                val pid = childPid.getAndSet(-1)
                if (pid > 0) terminateChild(pid)
                if (stdoutRead >= 0) close(stdoutRead)
                if (wakeRead >= 0) close(wakeRead)
                if (wakeWrite >= 0) close(wakeWrite)
            }
        }
    }

    fun stop() {
        if (started.value == 0) return
        stopRequested.value = 1
        val wfd = wakeWriteFd.value
        if (wfd >= 0) {
            memScoped {
                val b = alloc<ByteVar>()
                b.value = 1
                write(wfd, b.ptr, 1u)
            }
        }
        val pid = childPid.value
        if (pid > 0) kill(pid, SIGTERM)
        val activeJob = job
        if (activeJob != null) {
            runBlocking { withTimeoutOrNull(1_000L) { activeJob.join() } }
        }
        scope?.cancel()
        val remainingPid = childPid.getAndSet(-1)
        if (remainingPid > 0) terminateChild(remainingPid)
        job = null
        scope = null
        started.value = 0
    }
}

private fun setNonBlocking(fd: Int) {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags >= 0) fcntl(fd, F_SETFL, flags or O_NONBLOCK)
}

private fun terminateChild(pid: Int) {
    kill(pid, SIGTERM)
    memScoped {
        val status = alloc<IntVar>()
        repeat(10) {
            val waited = waitpid(pid, status.ptr, WNOHANG)
            if (waited == pid || (waited < 0 && errno != EINTR)) return
            usleep(20_000u)
        }
        kill(pid, SIGKILL)
        while (true) {
            val waited = waitpid(pid, status.ptr, 0)
            if (waited == pid || (waited < 0 && errno != EINTR)) break
        }
    }
}
