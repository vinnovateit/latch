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
     * Resolved once per process, and the first resolution moves any data a
     * pre-1.4.3 build left in the old place ([LegacyDataMigration]). That runs
     * before anything opens a data file, since everything reaches its file
     * through here.
     */
    private val windowsDataDir: File by lazy {
        val dir = File(File(localAppData, "VinnovateIT"), "Latch").apply { mkdirs() }
        legacyMigration = LegacyDataMigration.migrate(from = File(localAppData, "Latch"), to = dir)
        dir
    }

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
     * default is %LOCALAPPDATA%\Latch -- the same folder as [dataDir]. Every
     * Latch MSI removes its install directory recursively on uninstall
     * (jpackage's RemoveFolderEx), and a major upgrade uninstalls the old
     * product before installing the new one. A package staged under
     * [dataDir] sits inside what that step deletes, while msiexec is still
     * installing from it; the /qb fallback then points at a file that may no
     * longer exist. The per-user temp directory is outside any install
     * location and still private to the user.
     */
    val updatesDir: File
        get() = when {
            isWindows -> File(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"), "Latch-updates")
            else -> File(dataDir, "updates")
        }.apply { mkdirs() }

    /** DPAPI-encrypted credential blob. */
    val credentialsFile: File get() = File(dataDir, "credentials.bin")

    val settingsFile: File get() = File(dataDir, "settings.json")

    val databaseFile: File get() = File(dataDir, "latch_database")
}
