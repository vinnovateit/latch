package com.vinnovateit.latch.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The data directory, and so everything Latch writes into it, is private to
 * the user. Windows needs nothing set (see [AppPaths.ownerOnly]), so the
 * permission checks run where POSIX permissions exist.
 */
class AppPathsTest {
    private lateinit var root: File
    private var previousDataDir: String? = null

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("latch-app-paths-").toFile()
        previousDataDir = System.getProperty("latch.dataDir")
    }

    @AfterTest
    fun tearDown() {
        previousDataDir?.let { System.setProperty("latch.dataDir", it) } ?: System.clearProperty("latch.dataDir")
        root.deleteRecursively()
    }

    private fun permissionsOf(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `a new data directory is created owner-only`() {
        if (AppPaths.isWindows) return
        val data = File(root, "data")
        System.setProperty("latch.dataDir", data.absolutePath)

        assertEquals(data, AppPaths.dataDir)
        assertEquals("rwx------", permissionsOf(data))
    }

    @Test
    fun `an existing data directory that others can read is tightened`() {
        if (AppPaths.isWindows) return
        val data = File(root, "data").apply { mkdirs() }
        Files.setPosixFilePermissions(data.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        System.setProperty("latch.dataDir", data.absolutePath)

        AppPaths.dataDir

        assertEquals("rwx------", permissionsOf(data))
    }

    @Test
    fun `logs are kept inside the private data directory`() {
        val data = File(root, "data")
        System.setProperty("latch.dataDir", data.absolutePath)

        val logs = AppPaths.logsDir

        assertEquals(data, logs.parentFile)
        assertTrue(logs.isDirectory)
    }
}
