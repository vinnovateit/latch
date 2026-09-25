package com.vinnovateit.latch.desktop

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Moves user data out of the directory Windows builds up to 1.4.2 kept it in.
 *
 * That directory, %LOCALAPPDATA%\Latch, is also the MSI's default install
 * directory, and every Latch MSI removes its install directory recursively on
 * uninstall -- including the uninstall a major upgrade runs -- so settings,
 * saved credentials and session history went with every update. The data now
 * lives elsewhere (see [AppPaths.dataDir]); this carries an existing install's
 * data across the first time a fixed build starts.
 *
 * Every move is a plain rename on one volume, never replacing a file already
 * at the destination, so it is atomic per file and safe against a second Latch
 * process migrating at the same moment: whichever rename wins, the other finds
 * its source gone or its destination taken and leaves both alone. Anything
 * that cannot be moved that way stays where it is.
 */
internal object LegacyDataMigration {

    private const val DATABASE = "latch_database"

    /** Settings and credentials; each moves on its own. */
    private val FILES = listOf("credentials.bin", "settings.json")

    /**
     * The database's journal files go first and the database last: an
     * interrupted migration can then leave journal files ahead of their
     * database, which the next run completes, but never a database in the new
     * place with its uncommitted pages left behind in the old one.
     */
    private val DATABASE_FILES = listOf("$DATABASE-wal", "$DATABASE-shm", DATABASE)

    data class Result(val moved: List<String>, val kept: List<String>, val note: String? = null) {
        val isEmpty: Boolean get() = moved.isEmpty() && kept.isEmpty() && note == null
    }

    /**
     * [rename] is the move itself; tests replace it to simulate a file that
     * cannot be moved (say, held open by a virus scanner).
     */
    fun migrate(
        from: File,
        to: File,
        rename: (Path, Path) -> Unit = { source, target -> Files.move(source, target) },
    ): Result {
        if (!from.isDirectory) return Result(emptyList(), emptyList())
        if (from.canonicalFile == to.canonicalFile) return Result(emptyList(), emptyList())
        to.mkdirs()
        val sameVolume = runCatching {
            Files.getFileStore(from.toPath()) == Files.getFileStore(to.toPath())
        }.getOrDefault(false)
        if (!sameVolume) {
            // A cross-volume move is a copy and a delete, which an interruption
            // can leave half-done on the destination. Not worth that risk for a
            // setup this unusual; the data stays readable where it is.
            return Result(emptyList(), emptyList(), "legacy data is on another volume; left in ${from.path}")
        }

        val moved = mutableListOf<String>()
        val kept = mutableListOf<String>()
        /** False only when the file is still in the old place and could not be moved. */
        fun move(name: String): Boolean {
            val source = File(from, name)
            if (!source.isFile) return true
            return try {
                rename(source.toPath(), File(to, name).toPath())
                moved += name
                true
            } catch (_: FileAlreadyExistsException) {
                kept += name
                true
            } catch (_: NoSuchFileException) {
                // Moved by another process a moment ago.
                true
            } catch (_: IOException) {
                kept += name
                false
            }
        }

        FILES.forEach { move(it) }
        // A database already in the new place is the live one; its journal
        // must never be joined by the old database's.
        if (File(to, DATABASE).exists()) {
            DATABASE_FILES.filter { File(from, it).isFile }.forEach { kept += it }
        } else {
            // Stops at the first file that cannot be moved, so the database
            // never leaves its journal behind within one run either: the rest
            // stay together in the old place and the next start retries them.
            for ((index, name) in DATABASE_FILES.withIndex()) {
                if (!move(name)) {
                    DATABASE_FILES.drop(index + 1).filter { File(from, it).isFile }.forEach { kept += it }
                    break
                }
            }
        }
        return Result(moved, kept)
    }
}
