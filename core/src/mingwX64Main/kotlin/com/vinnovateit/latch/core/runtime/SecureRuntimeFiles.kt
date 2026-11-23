@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.JFileWriter
import com.vinnovateit.latch.core.mp.useOutput
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.Json
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.open
import platform.posix.write

/** Runtime owner lock/metadata/token, stored as small JSON/text files. */
class SecureRuntimeFiles internal constructor(private val dataDir: JFile) {
    init { dataDir.mkdirs() }

    val lockFile: JFile get() = JFile(dataDir, ".runtime.lock")
    val metadataFile: JFile get() = JFile(dataDir, ".runtime.json")
    val tokenFile: JFile get() = JFile(dataDir, ".runtime.token")

    private val json = Json { ignoreUnknownKeys = false }

    fun createToken(): String {
        val bytes = ByteArray(32) { platform.posix.rand().toByte() }
        val token = bytes.joinToString("") { ((it.toInt() and 0xFF) or 0x80).toString(16).padStart(2, '0') }
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
        val tmp = JFile(destination.path + ".tmp")
        JFileWriter(tmp, false).useOutput { it.write(content.encodeToByteArray()) }
        if (destination.exists() && !destination.delete()) error("Cannot replace ${destination.path}")
        check(tmp.renameTo(destination)) { "Cannot write ${destination.path}" }
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
