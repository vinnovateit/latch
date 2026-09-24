package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json

/**
 * The runtime owner's lock, metadata and IPC token.
 *
 * The token authenticates every IPC request and the metadata says which
 * process owns the runtime, so a torn write of either breaks ownership: other
 * processes would find the lock held and no usable owner behind it. Both are
 * written through [SecureFileWriter], the same all-or-nothing replacement the
 * credential stores use, and a filesystem that cannot rename atomically fails
 * the write -- the owner then fails to start cleanly instead of publishing a
 * partial token. On POSIX filesystems the temp file is owner-only before the
 * token is written into it.
 */
class SecureRuntimeFiles internal constructor(
    private val dataDir: File,
    private val writer: SecureFileWriter,
) {
    constructor(dataDir: File) : this(dataDir, SecureFileWriter(ownerOnly = supportsPosix(dataDir.apply { mkdirs() })))

    val lockFile: File get() = dataDir.resolve(".runtime.lock")
    val metadataFile: File get() = dataDir.resolve(".runtime.json")
    val tokenFile: File get() = dataDir.resolve(".runtime.token")

    private val json = Json { ignoreUnknownKeys = false }

    init {
        dataDir.mkdirs()
    }

    fun createToken(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also(::writeToken)
    }

    fun writeToken(token: String) {
        atomicWrite(tokenFile, token)
    }

    fun readToken(): String? = runCatching {
        tokenFile.readText(Charsets.UTF_8).trim().takeIf(String::isNotEmpty)
    }.getOrNull()

    fun writeMetadata(metadata: OwnerMetadata) {
        atomicWrite(metadataFile, json.encodeToString(metadata))
    }

    fun readMetadata(): OwnerMetadata? = runCatching {
        json.decodeFromString<OwnerMetadata>(metadataFile.readText(Charsets.UTF_8))
    }.getOrNull()

    fun clearOwnerState() {
        runCatching { metadataFile.delete() }
        runCatching { tokenFile.delete() }
    }

    private fun atomicWrite(destination: File, content: String) {
        writer.replace(destination, content.toByteArray(Charsets.UTF_8))
        restrictToOwner(destination)
    }

    private fun restrictToOwner(file: File) {
        val path = file.toPath()
        if (supportsPosix(file)) {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } else {
            file.setReadable(false, false)
            file.setWritable(false, false)
            check(file.setReadable(true, true)) { "Unable to make ${file.name} owner-readable" }
            check(file.setWritable(true, true)) { "Unable to make ${file.name} owner-writable" }
        }
    }
}

private fun supportsPosix(file: File): Boolean =
    runCatching { Files.getFileStore(file.toPath()).supportsFileAttributeView("posix") }.getOrDefault(false)
