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
 * its source gone or its destination taken and leaves both alone.
 *
 * Settings and credentials ([migrate]) move whenever the data directory is
 * first resolved. The database ([migrateDatabase]) moves separately, from
 * [AppPaths.databaseLocation], because the outcome decides which database the
 * process opens and that decision must be taken by the one process allowed to
 * open it.
 */
internal object LegacyDataMigration {

    private const val DATABASE = "latch_database"

    /** Settings and credentials; each moves on its own. */
    private val FILES = listOf("credentials.bin", "settings.json")

    /**
     * The database's journal files go first and the database last, so no
     * interruption can leave the database in the new place ahead of its
     * uncommitted pages.
     */
    private val DATABASE_FILES = listOf("$DATABASE-wal", "$DATABASE-shm", DATABASE)

    private val defaultRename: (Path, Path) -> Unit = { source, target -> Files.move(source, target) }

    data class Result(val moved: List<String>, val kept: List<String>, val note: String? = null) {
        val isEmpty: Boolean get() = moved.isEmpty() && kept.isEmpty() && note == null
    }

    /** What happened to the legacy database, and so which database may be opened. */
    enum class DatabaseState {
        /** No legacy database. Open the new one. */
        NONE,

        /** The whole legacy set moved. Open the new one. */
        COMPLETE,

        /**
         * A database already exists in the new place and is the live one. Any
         * legacy remnants are left untouched, never joined to it.
         */
        DESTINATION_ALREADY_LIVE,

        /**
         * The set could not be moved this time, but is still whole in the old
         * place. Open it there; the next start tries again.
         */
        RETRY_WITH_LEGACY,

        /**
         * The set is split between the two places and could not be reunited.
         * Neither half may be opened -- the database without its journal loses
         * its uncommitted pages, and reuniting them later would replay a stale
         * journal over newer data -- and no fresh database may be created
         * beside either. The next start tries again.
         */
        BLOCKED,
    }

    data class DatabaseResult(val state: DatabaseState, val moved: List<String> = emptyList(), val detail: String? = null)

    /**
     * [rename] is the move itself; tests replace it to simulate a file that
     * cannot be moved (say, held open by a virus scanner).
     */
    fun migrate(from: File, to: File, rename: (Path, Path) -> Unit = defaultRename): Result {
        if (!from.isDirectory) return Result(emptyList(), emptyList())
        if (from.canonicalFile == to.canonicalFile) return Result(emptyList(), emptyList())
        to.mkdirs()
        if (!sameVolume(from, to)) {
            // A cross-volume move is a copy and a delete, which an interruption
            // can leave half-done on the destination. Not worth that risk for a
            // setup this unusual; the data stays readable where it is.
            return Result(emptyList(), emptyList(), "legacy data is on another volume; left in ${from.path}")
        }
        val moved = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (name in FILES) {
            val source = File(from, name)
            if (!source.isFile) continue
            try {
                rename(source.toPath(), File(to, name).toPath())
                moved += name
            } catch (_: NoSuchFileException) {
                // Moved by another process a moment ago.
            } catch (_: IOException) {
                // Already present at the destination (FileAlreadyExistsException),
                // or not movable right now: left where it is.
                kept += name
            }
        }
        return Result(moved, kept)
    }

    /**
     * Moves the legacy database set -- all of it or none of it -- and says
     * which database the caller may open. See [DatabaseState].
     *
     * If a file of the set cannot be moved, the files this call already moved
     * are renamed back, so the set is whole in the old place again. A journal
     * already present at the destination while the old place still has its
     * own is a conflict, never resolved by picking one.
     */
    fun migrateDatabase(from: File, to: File, rename: (Path, Path) -> Unit = defaultRename): DatabaseResult {
        if (!from.isDirectory || from.canonicalFile == to.canonicalFile) return DatabaseResult(DatabaseState.NONE)
        val legacy = DATABASE_FILES.filter { File(from, it).isFile }
        if (legacy.isEmpty()) return DatabaseResult(DatabaseState.NONE)
        if (File(to, DATABASE).exists()) {
            return DatabaseResult(DatabaseState.DESTINATION_ALREADY_LIVE, detail = "left in ${from.path}: ${legacy.joinToString()}")
        }
        if (!File(from, DATABASE).isFile) {
            // Journals with no database are not a set to carry across: next
            // to a new database, SQLite would apply their pages to it.
            return DatabaseResult(DatabaseState.NONE, detail = "left orphaned journal files in ${from.path}: ${legacy.joinToString()}")
        }
        to.mkdirs()
        if (!sameVolume(from, to)) {
            return settle(from, to, "legacy database is on another volume")
        }

        val movedNow = mutableListOf<String>()
        var failure: String? = null
        for (name in DATABASE_FILES) {
            val source = File(from, name)
            // Already moved by an interrupted earlier run, or never existed.
            if (!source.isFile) continue
            try {
                rename(source.toPath(), File(to, name).toPath())
                movedNow += name
            } catch (_: NoSuchFileException) {
                // Moved by another process a moment ago.
            } catch (_: FileAlreadyExistsException) {
                failure = "$name exists in both places"
                break
            } catch (e: IOException) {
                failure = "$name could not be moved (${e.javaClass.simpleName})"
                break
            }
        }
        if (failure == null) return DatabaseResult(DatabaseState.COMPLETE, movedNow)

        // Put back what this call moved, newest first, so the set is whole
        // in the old place again.
        for (name in movedNow.asReversed()) {
            try {
                rename(File(to, name).toPath(), File(from, name).toPath())
            } catch (_: IOException) {
                // Leaves a split set; settle() reports it as BLOCKED.
            }
        }
        return settle(from, to, failure)
    }

    /** RETRY_WITH_LEGACY if the set is wholly in the old place, BLOCKED if any of it is in the new one. */
    private fun settle(from: File, to: File, reason: String): DatabaseResult {
        val inNewPlace = DATABASE_FILES.filter { File(to, it).exists() }
        return if (inNewPlace.isEmpty() && File(from, DATABASE).isFile) {
            DatabaseResult(DatabaseState.RETRY_WITH_LEGACY, detail = reason)
        } else {
            DatabaseResult(DatabaseState.BLOCKED, detail = "$reason; in the new place: ${inNewPlace.joinToString()}")
        }
    }

    private fun sameVolume(from: File, to: File): Boolean = runCatching {
        Files.getFileStore(from.toPath()) == Files.getFileStore(to.toPath())
    }.getOrDefault(false)
}
