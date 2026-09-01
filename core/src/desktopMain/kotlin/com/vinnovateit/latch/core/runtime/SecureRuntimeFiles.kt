package com.vinnovateit.latch.core.runtime

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json

class SecureRuntimeFiles(private val dataDir: File) {
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
        destination.parentFile?.mkdirs()
        val temporary = Files.createTempFile(destination.parentFile.toPath(), destination.name, ".tmp")
        try {
            Files.writeString(temporary, content, Charsets.UTF_8)
            restrictToOwner(temporary.toFile())
            try {
                Files.move(
                    temporary,
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            restrictToOwner(destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun restrictToOwner(file: File) {
        val path = file.toPath()
        val posix = runCatching { Files.getFileStore(path).supportsFileAttributeView("posix") }.getOrDefault(false)
        if (posix) {
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
