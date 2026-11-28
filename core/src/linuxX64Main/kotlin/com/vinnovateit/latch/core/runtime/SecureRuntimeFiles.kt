@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.nowMillis
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import platform.posix.EINTR
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getpid
import platform.posix.open
import platform.posix.rand
import platform.posix.rename
import platform.posix.srand
import platform.posix.unlink
import platform.posix.write

private val writeSeq = AtomicInt(0)
private val fallbackRngSeeded = AtomicInt(0)

internal fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    val urandom = fopen("/dev/urandom", "rb")
    if (urandom != null) {
        try {
            var offset = 0
            while (offset < count) {
                val n = bytes.usePinned { pinned ->
                    fread(pinned.addressOf(offset), 1u, (count - offset).toULong(), urandom).toInt()
                }
                if (n <= 0) break
                offset += n
            }
            if (offset == count) return bytes
        } finally {
            fclose(urandom)
        }
    }
    if (fallbackRngSeeded.compareAndSet(0, 1)) {
        srand((nowMillis() xor getpid().toLong()).toUInt())
    }
    for (i in 0 until count) bytes[i] = rand().toByte()
    return bytes
}

/** Runtime owner lock/metadata/token, stored as small JSON/text files. */
class SecureRuntimeFiles internal constructor(private val dataDir: JFile) {
    init { dataDir.mkdirs() }

    val lockFile: JFile get() = JFile(dataDir, ".runtime.lock")
    val metadataFile: JFile get() = JFile(dataDir, ".runtime.json")
    val tokenFile: JFile get() = JFile(dataDir, ".runtime.token")

    private val json = Json { ignoreUnknownKeys = false }

    fun createToken(): String {
        val bytes = secureRandomBytes(32)
        val token = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        writeToken(token)
        return token
    }

    fun writeToken(token: String) = atomicWrite(tokenFile, token)

    fun readToken(): String? = runCatching {
        tokenFile.readTextCompat().trim().takeIf(String::isNotEmpty)
    }.getOrNull()

    fun writeMetadata(metadata: OwnerMetadata) = atomicWrite(metadataFile, json.encodeToString(metadata))

    fun readMetadata(): OwnerMetadata? = runCatching {
        json.decodeFromString<OwnerMetadata>(metadataFile.readTextCompat())
    }.getOrNull()

    fun clearOwnerState() {
        runCatching { metadataFile.delete() }
        runCatching { tokenFile.delete() }
    }

    private fun atomicWrite(destination: JFile, content: String) {
        dataDir.mkdirs()
        val tmpPath = "${destination.path}.tmp.${getpid()}.${writeSeq.addAndGet(1)}"
        val fd = open(tmpPath, O_WRONLY or O_CREAT or O_TRUNC, 0x180u)
        if (fd < 0) error("Cannot write $tmpPath (errno=$errno)")
        var fdClosed = false
        var renamed = false
        try {
            val bytes = content.encodeToByteArray()
            var offset = 0
            bytes.usePinned { pinned ->
                while (offset < bytes.size) {
                    val written = write(fd, pinned.addressOf(offset), (bytes.size - offset).toULong()).toInt()
                    if (written < 0) {
                        if (errno == EINTR) continue
                        error("Failed writing $tmpPath (errno=$errno)")
                    }
                    if (written == 0) error("Short write to $tmpPath")
                    offset += written
                }
            }
            chmod(tmpPath, 0x180u)
            close(fd)
            fdClosed = true
            if (rename(tmpPath, destination.path) != 0) {
                error("Cannot rename $tmpPath to ${destination.path} (errno=$errno)")
            }
            renamed = true
        } finally {
            if (!fdClosed) close(fd)
            if (!renamed) unlink(tmpPath)
        }
    }
}

internal fun JFile.readTextCompat(): String = readFileText(this)

private fun readFileText(file: JFile): String {
    val handle = platform.posix.fopen(file.path, "r") ?: error("Cannot open ${file.path}")
    return try {
        val out = StringBuilder()
        val buffer = ByteArray(8192)
        while (true) {
            val n = platform.posix.fread(buffer.refTo(0), 1u, buffer.size.toULong(), handle).toInt()
            if (n <= 0) break
            out.append(buffer.decodeToString(0, n))
        }
        out.toString()
    } finally { platform.posix.fclose(handle) }
}
