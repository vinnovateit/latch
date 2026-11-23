@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.userHome
import kotlinx.cinterop.toKString

/** Native equivalent of the JVM desktop AppPaths. */
object AppPaths {
    val dataDir: JFile by lazy {
        val override = platform.posix.getenv("LATCH_DATA_DIR")?.toKString()
        val localAppData = platform.posix.getenv("LOCALAPPDATA")?.toKString()?.takeIf(String::isNotBlank)
        val base = override?.takeIf { it.isNotBlank() }
            ?: localAppData?.let { "$it\\Latch" }
            ?: "${userHome()}\\.latch"
        JFile(base)
    }
    val logsDir: JFile get() = JFile(dataDir, "logs").also { it.mkdirs() }
    val settingsFile: JFile get() = JFile(dataDir, "settings.json")
    val credentialsFile: JFile get() = JFile(dataDir, "credentials.json")

    sealed interface DatabaseLocation {
        data class OnDisk(val file: JFile) : DatabaseLocation
        data object InMemory : DatabaseLocation
    }

    val databaseLocation: DatabaseLocation get() = DatabaseLocation.OnDisk(JFile(dataDir, "latch.db"))
}
