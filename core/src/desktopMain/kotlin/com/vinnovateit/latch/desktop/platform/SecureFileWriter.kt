package com.vinnovateit.latch.desktop.platform

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Replaces a file's contents all-or-nothing, for credential state where a torn
 * or half-replaced file would leave the user with nothing usable.
 *
 * Bytes go to a temp file in the destination's own directory, are forced to
 * disk, and only then is the temp file renamed over the destination. Any
 * failure throws, removes the temp file, and leaves the previous destination
 * untouched.
 *
 * There is deliberately no non-atomic fallback: if the filesystem cannot rename
 * atomically, the move throws [java.nio.file.AtomicMoveNotSupportedException]
 * and the write fails rather than risking a partially replaced file.
 *
 * With [ownerOnly], the temp file is created as POSIX 0600 before any bytes are
 * written, and a filesystem that cannot confirm those permissions fails the
 * write rather than holding secret-bearing bytes under wider access.
 */
internal class SecureFileWriter(
    private val ownerOnly: Boolean,
    private val createTemp: (directory: Path, prefix: String) -> Path =
        if (ownerOnly) ::createOwnerOnlyTemp else ::createDefaultTemp,
    private val move: (source: Path, target: Path) -> Unit = ::atomicMove,
) {
    fun replace(destination: File, bytes: ByteArray) {
        val directory = destination.absoluteFile.parentFile.toPath()
        Files.createDirectories(directory)
        val temporary = createTemp(directory, "${destination.name}.")
        try {
            if (ownerOnly) {
                check(Files.getPosixFilePermissions(temporary) == OWNER_ONLY) {
                    "Unable to restrict ${destination.name} to its owner"
                }
            }
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            move(temporary, destination.toPath())
            syncDirectory(directory)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private companion object {
        val OWNER_ONLY: Set<PosixFilePermission> =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        fun createOwnerOnlyTemp(directory: Path, prefix: String): Path =
            Files.createTempFile(directory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))

        fun createDefaultTemp(directory: Path, prefix: String): Path =
            Files.createTempFile(directory, prefix, ".tmp")

        fun atomicMove(source: Path, target: Path) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        }

        /**
         * Makes the rename itself durable where the OS allows syncing a
         * directory (Linux). Windows cannot open a directory as a channel; the
         * rename there is still atomic, just not forced, and a crash at worst
         * leaves the previous file in place.
         */
        fun syncDirectory(directory: Path) {
            try {
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
            }
        }
    }
}
