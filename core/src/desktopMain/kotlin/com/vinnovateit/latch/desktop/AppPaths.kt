package com.vinnovateit.latch.desktop

import java.io.File

/**
 * Where Latch keeps its data on disk.
 *
 * Uses %LOCALAPPDATA% on Windows to match `perUserInstall = true` in the MSI
 * config -- both stay inside the user profile, so no elevation is ever needed.
 * The data and the install must not share a directory, though: see
 * [windowsDataDir].
 */
object AppPaths {

    val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    val isMac: Boolean =
        System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    val isLinux: Boolean = !isWindows && !isMac

    val dataDir: File
        get() {
            System.getProperty("latch.dataDir")?.takeIf(String::isNotBlank)?.let { override ->
                return File(override).apply { mkdirs() }
            }
            if (isWindows) return windowsDataDir.apply { mkdirs() }
            val base = when {
                isMac -> System.getProperty("user.home") + "/Library/Application Support"

                // Linux / other: honour XDG if set.
                else -> System.getenv("XDG_DATA_HOME")
                    ?: (System.getProperty("user.home") + "/.local/share")
            }
            return File(base, "Latch").apply { mkdirs() }
        }

    private val localAppData: String
        get() = System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Local")

    /**
     * %LOCALAPPDATA%\VinnovateIT\Latch.
     *
     * Up to 1.4.2 the data lived in %LOCALAPPDATA%\Latch, which is also the
     * MSI's default install directory (INSTALLDIR). Every Latch MSI removes its
     * install directory recursively on uninstall -- and a major upgrade
     * uninstalls the old product -- so each update deleted the user's
     * settings, saved credentials and history along with the old binaries.
     * No Latch install ever lands in this directory, so neither an upgrade
     * nor an uninstall touches it.
     *
     * Resolved once per process, and the first resolution moves the settings
     * and credentials a pre-1.4.3 build left in the old place
     * ([LegacyDataMigration.migrate]). That runs before anything opens a data
     * file, since everything reaches its file through here. The database is
     * not moved here: see [databaseLocation].
     */
    private val windowsDataDir: File by lazy {
        val dir = File(File(localAppData, "VinnovateIT"), "Latch").apply { mkdirs() }
        legacyMigration = LegacyDataMigration.migrate(from = legacyWindowsDataDir, to = dir)
        dir
    }

    /** Where Windows builds up to 1.4.2 kept their data: the default install directory. */
    private val legacyWindowsDataDir: File get() = File(localAppData, "Latch")

    /** What moving pre-1.4.3 Windows data did in this process, for the log; null if it never ran. */
    @Volatile
    internal var legacyMigration: LegacyDataMigration.Result? = null
        private set

    val logsDir: File get() = File(dataDir, "logs").apply { mkdirs() }

    /**
     * Downloaded update packages. Deliberately not a temp *file*: the JVM
     * exits moments after handing the path to msiexec, so anything cleaned up
     * on JVM shutdown would be racing the installer that still needs to read
     * it. Swept on startup instead -- see GithubUpdater.cleanStaleDownloads.
     *
     * On Windows this must stay outside the install directory, which by
     * default is %LOCALAPPDATA%\Latch. Every Latch MSI removes its install
     * directory recursively on uninstall (jpackage's RemoveFolderEx), and a
     * major upgrade uninstalls the old product before installing the new
     * one, so a package staged inside it is deleted while msiexec is still
     * installing from it. Builds up to 1.4.2 did exactly that: they staged
     * under their data directory, which was then that same folder. The data
     * directory has since moved out ([windowsDataDir]), but the installer can
     * be pointed anywhere (dirChooser), so staging uses the per-user temp
     * directory, which is outside any install location and still private to
     * the user.
     */
    val updatesDir: File
        get() = when {
            isWindows -> File(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"), "Latch-updates")
            else -> File(dataDir, "updates")
        }.apply { mkdirs() }

    /** DPAPI-encrypted credential blob. */
    val credentialsFile: File get() = File(dataDir, "credentials.bin")

    val settingsFile: File get() = File(dataDir, "settings.json")

    /** Which database this process opens: a file, or -- when no file may be opened -- memory only. */
    sealed interface DatabaseLocation {
        data class OnDisk(val file: File) : DatabaseLocation
        data object InMemory : DatabaseLocation
    }

    /**
     * The database this process opens, decided once, the first time it is
     * asked for -- by buildDatabase, which only the runtime owner calls, after
     * it holds the runtime lock. So exactly one process at a time moves the
     * legacy database and chooses which one to open, and no other process has
     * it open while it does.
     */
    val databaseLocation: DatabaseLocation by lazy {
        val legacy = if (isWindows && System.getProperty("latch.dataDir").isNullOrBlank()) legacyWindowsDataDir else null
        chooseDatabaseLocation(legacy, dataDir).also { (_, result) -> databaseMigration = result }.first
    }

    /** What moving the pre-1.4.3 database did in this process, for the log; null if there was nothing to consider. */
    @Volatile
    internal var databaseMigration: LegacyDataMigration.DatabaseResult? = null
        private set

    /**
     * The one mapping from the migration outcome to the database to open.
     * A fresh database is only ever created in [dataDir], and only when no
     * legacy database is waiting to move there: a failed move keeps using the
     * legacy database where it is whole, and uses no file at all where it is
     * split, until a later start can move it.
     */
    internal fun chooseDatabaseLocation(
        legacyDir: File?,
        dataDir: File,
        rename: (java.nio.file.Path, java.nio.file.Path) -> Unit = { source, target -> java.nio.file.Files.move(source, target) },
    ): Pair<DatabaseLocation, LegacyDataMigration.DatabaseResult?> {
        val current = DatabaseLocation.OnDisk(File(dataDir, DATABASE_NAME))
        if (legacyDir == null) return current to null
        val result = LegacyDataMigration.migrateDatabase(legacyDir, dataDir, rename)
        val location = when (result.state) {
            LegacyDataMigration.DatabaseState.NONE,
            LegacyDataMigration.DatabaseState.COMPLETE,
            LegacyDataMigration.DatabaseState.DESTINATION_ALREADY_LIVE,
            -> current
            LegacyDataMigration.DatabaseState.RETRY_WITH_LEGACY -> DatabaseLocation.OnDisk(File(legacyDir, DATABASE_NAME))
            LegacyDataMigration.DatabaseState.BLOCKED -> DatabaseLocation.InMemory
        }
        return location to result
    }

    private const val DATABASE_NAME = "latch_database"
}
