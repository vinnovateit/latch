@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.JFileWriter
import com.vinnovateit.latch.core.mp.useOutput
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.ECHO
import platform.posix.O_RDONLY
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TCSANOW
import platform.posix.TIOCGWINSZ
import platform.posix.close
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv
import platform.posix.ioctl
import platform.posix.isatty
import platform.posix.open
import platform.posix.readlink
import platform.posix.system
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios
import platform.posix.winsize

/** The one ":core" file type, kept under the old name so the CLI reads as before. */
internal typealias CliFile = JFile

// --- environment -----------------------------------------------------------

internal object CliEnv {
    val osName: String = "Linux"
    val userHome: String get() = get("HOME") ?: "/root"
    val xdgDataHome: String? get() = get("XDG_DATA_HOME")
    val xdgConfigHome: String? get() = get("XDG_CONFIG_HOME")

    operator fun get(name: String): String? = getenv(name)?.toKString()
    fun contains(name: String): Boolean = get(name) != null
    fun orEmpty(name: String): String = get(name).orEmpty()

    /** All variables, read from the kernel so no JNI-style environment access is needed. */
    fun all(): Map<String, String> = CliFile("/proc/self/environ").readText()
        .split('\u0000')
        .mapNotNull { entry -> entry.indexOf('=').takeIf { it > 0 }?.let { entry.substring(0, it) to entry.substring(it + 1) } }
        .toMap()
}

// --- paths -----------------------------------------------------------------

/** Native equivalent of core's desktop AppPaths; LATCH_DATA_DIR mirrors -Dlatch.dataDir. */
internal object CliAppPaths {
    val isWindows: Boolean = false
    val isLinux: Boolean = true

    val dataDir: CliFile
        get() {
            CliEnv["LATCH_DATA_DIR"]?.takeIf(String::isNotBlank)?.let { override ->
                return CliFile(override).also { it.mkdirs() }
            }
            val base = CliEnv.xdgDataHome ?: (CliEnv.userHome + "/.local/share")
            return CliFile(base).resolve("Latch").also { it.mkdirs() }
        }

    val logsDir: CliFile get() = dataDir.resolve("logs").also { it.mkdirs() }
}

internal fun CliFile.resolve(child: String): CliFile = CliFile(path.trimEnd('/') + "/" + child)
internal val CliFile.absolutePath: String get() = path
internal val CliFile.nameWithoutExtension: String get() = name.substringBeforeLast('.', name)

internal fun CliFile.writeText(text: String) {
    JFileWriter(this).useOutput { it.write(text.encodeToByteArray()) }
}

internal fun CliFile.readText(): String {
    val handle = fopen(path, "rb") ?: error("Cannot read $path")
    return try {
        val bytes = mutableListOf<Byte>()
        val buffer = ByteArray(8192)
        while (true) {
            val read = fread(buffer.refTo(0), 1u, 8192u, handle).toInt()
            if (read <= 0) break
            for (i in 0 until read) bytes.add(buffer[i])
        }
        bytes.toByteArray().decodeToString()
    } finally {
        fclose(handle)
    }
}

// --- terminal --------------------------------------------------------------

internal object CliConsole {
    val isAvailable: Boolean get() = stdinIsTty && stdoutIsTty
    val stdinIsTty: Boolean get() = isatty(STDIN_FILENO) != 0
    val stdoutIsTty: Boolean get() = isatty(STDOUT_FILENO) != 0

    fun readLine(prompt: String): String? {
        if (!stdinIsTty) print(prompt)
        return readlnOrNull()
    }

    /** Reads a line with terminal echo disabled; no TTY means no secret, as before. */
    fun readSecret(prompt: String): CharArray? {
        if (!stdinIsTty) return null
        print(prompt)
        val originalFlag: UInt? = memScoped {
            val term = alloc<termios>()
            if (tcgetattr(STDIN_FILENO, term.ptr) != 0) {
                null
            } else {
                val saved = term.c_lflag
                term.c_lflag = saved and ECHO.toUInt().inv()
                tcsetattr(STDIN_FILENO, TCSANOW, term.ptr)
                saved
            }
        }
        return try {
            readlnOrNull()?.toCharArray()
        } finally {
            if (originalFlag != null) memScoped {
                val term = alloc<termios>()
                tcgetattr(STDIN_FILENO, term.ptr)
                term.c_lflag = originalFlag
                tcsetattr(STDIN_FILENO, TCSANOW, term.ptr)
            }
            println()
        }
    }
}

internal fun terminalWidthFromIoctl(): Int? {
    val fd = open("/dev/tty", O_RDONLY)
    if (fd < 0) return null
    return try {
        memScoped {
            val size = alloc<winsize>()
            if (ioctl(fd, TIOCGWINSZ.toULong(), size.ptr) == 0 && size.ws_col.toInt() > 0) size.ws_col.toInt() else null
        }
    } finally {
        close(fd)
    }
}

// --- identifiers -----------------------------------------------------------

/** Same textual form as UUID.randomUUID(), without java.util. */
internal fun randomRequestId(): String {
    val bytes = ByteArray(16).also { kotlin.random.Random.nextBytes(it) }
    bytes[6] = (bytes[6].toInt() and 0x0F or 0x40).toByte()
    bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte()
    fun hex(index: Int) = (bytes[index].toInt() and 0xFF).toString(16).padStart(2, '0')
    return "${hex(0)}${hex(1)}${hex(2)}${hex(3)}-${hex(4)}${hex(5)}-" +
        "${hex(6)}${hex(7)}-${hex(8)}${hex(9)}-" +
        "${hex(10)}${hex(11)}${hex(12)}${hex(13)}${hex(14)}${hex(15)}"
}

// --- process / executable --------------------------------------------------

internal fun currentExecutable(): String? {
    val buffer = ByteArray(4096)
    val length = readlink("/proc/self/exe", buffer.refTo(0), 4095u).toInt()
    return if (length in 1..4095) buffer.decodeToString(0, length) else null
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Blocking command runner with output discarded, as ProcessBuilder.Redirect.DISCARD did. */
internal fun runCommandBlocking(command: List<String>): Int =
    system(command.joinToString(" ") { shellQuote(it) } + " > /dev/null 2>&1")

/** Detached launch: stdin from /dev/null, output appended to [logFile]. */
internal fun launchDetached(command: List<String>, logFile: CliFile) {
    val shell = command.joinToString(" ") { shellQuote(it) } +
        " >> ${shellQuote(logFile.path)} 2>&1 < /dev/null &"
    system(shell)
}
