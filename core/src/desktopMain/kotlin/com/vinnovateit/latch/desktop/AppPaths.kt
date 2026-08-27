package com.vinnovateit.latch.desktop

import java.io.File

/**
 * Where Latch keeps its data on disk.
 *
 * Uses %LOCALAPPDATA% on Windows to match `perUserInstall = true` in the MSI
 * config -- both stay inside the user profile, so no elevation is ever needed.
 */
object AppPaths {

    val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    val isMac: Boolean =
        System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    val isLinux: Boolean = !isWindows && !isMac

    val dataDir: File by lazy {
        val base = when {
            isWindows -> System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home") + "\\AppData\\Local"

            isMac -> System.getProperty("user.home") + "/Library/Application Support"

            // Linux / other: honour XDG if set.
            else -> System.getenv("XDG_DATA_HOME")
                ?: (System.getProperty("user.home") + "/.local/share")
        }
        File(base, "Latch").apply { mkdirs() }
    }

    val logsDir: File by lazy { File(dataDir, "logs").apply { mkdirs() } }

    /**
     * Downloaded update MSIs. Deliberately not a temp file: the JVM exits
     * moments after handing the path to msiexec, so anything cleaned up on JVM
     * shutdown would be racing the installer that still needs to read it.
     * Swept on startup instead -- see GithubUpdater.cleanStaleDownloads.
     */
    val updatesDir: File by lazy { File(dataDir, "updates").apply { mkdirs() } }

    /** DPAPI-encrypted credential blob. */
    val credentialsFile: File get() = File(dataDir, "credentials.bin")

    val settingsFile: File get() = File(dataDir, "settings.json")

    val databaseFile: File get() = File(dataDir, "latch_database")
}
