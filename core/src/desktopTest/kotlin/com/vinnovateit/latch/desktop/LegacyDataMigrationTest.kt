package com.vinnovateit.latch.desktop

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Moving pre-1.4.3 Windows data out of the MSI's install directory. OS-agnostic,
 * so the Windows CI job runs it against a real Windows filesystem as well.
 */
class LegacyDataMigrationTest {
    private lateinit var root: File
    private lateinit var legacy: File
    private lateinit var current: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("latch-migration-").toFile()
        legacy = File(root, "Latch").apply { mkdirs() }
        current = File(File(root, "VinnovateIT"), "Latch")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(dir: File, name: String, text: String) = File(dir, name).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    @Test
    fun `settings, credentials and the database move to the new directory intact`() {
        for (name in listOf("credentials.bin", "settings.json", "latch_database", "latch_database-wal", "latch_database-shm")) {
            write(legacy, name, "old $name")
        }

        val result = LegacyDataMigration.migrate(legacy, current)

        assertEquals(
            listOf("credentials.bin", "settings.json", "latch_database-wal", "latch_database-shm", "latch_database"),
            result.moved,
        )
        for (name in result.moved) {
            assertEquals("old $name", File(current, name).readText())
            assertFalse(File(legacy, name).exists(), "$name must be moved, not copied")
        }
    }

    @Test
    fun `a second run moves nothing`() {
        write(legacy, "settings.json", "old")
        LegacyDataMigration.migrate(legacy, current)

        val again = LegacyDataMigration.migrate(legacy, current)

        assertTrue(again.isEmpty)
        assertEquals("old", File(current, "settings.json").readText())
    }

    @Test
    fun `a file already in the new directory is never replaced`() {
        write(legacy, "settings.json", "old")
        write(current, "settings.json", "new")

        val result = LegacyDataMigration.migrate(legacy, current)

        assertEquals(listOf("settings.json"), result.kept)
        assertEquals("new", File(current, "settings.json").readText())
        assertEquals("old", File(legacy, "settings.json").readText(), "the unmoved copy is left where it was")
    }

    @Test
    fun `an old journal never joins a database already in the new directory`() {
        write(legacy, "latch_database", "old db")
        write(legacy, "latch_database-wal", "old wal")
        write(current, "latch_database", "live db")

        val result = LegacyDataMigration.migrate(legacy, current)

        assertFalse(File(current, "latch_database-wal").exists())
        assertEquals("live db", File(current, "latch_database").readText())
        assertEquals(listOf("latch_database-wal", "latch_database"), result.kept)
    }

    @Test
    fun `an interrupted database move is completed by the next run`() {
        // The first run moved the journal and stopped before the database.
        write(current, "latch_database-wal", "old wal")
        write(legacy, "latch_database-shm", "old shm")
        write(legacy, "latch_database", "old db")

        LegacyDataMigration.migrate(legacy, current)

        assertEquals("old db", File(current, "latch_database").readText())
        assertEquals("old shm", File(current, "latch_database-shm").readText())
        assertEquals("old wal", File(current, "latch_database-wal").readText())
    }

    @Test
    fun `a journal that cannot be moved keeps the database with it`() {
        write(legacy, "latch_database", "old db")
        write(legacy, "latch_database-wal", "old wal")
        write(legacy, "latch_database-shm", "old shm")
        write(legacy, "settings.json", "old settings")
        val lockedWal: (java.nio.file.Path, java.nio.file.Path) -> Unit = { source, target ->
            if (source.fileName.toString() == "latch_database-wal") throw java.io.IOException("held open by a scanner")
            java.nio.file.Files.move(source, target)
        }

        val result = LegacyDataMigration.migrate(legacy, current, lockedWal)

        assertEquals(listOf("settings.json"), result.moved, "other files still move")
        assertEquals(listOf("latch_database-wal", "latch_database-shm", "latch_database"), result.kept)
        assertFalse(File(current, "latch_database").exists(), "the database must not move without its journal")
        assertEquals("old db", File(legacy, "latch_database").readText())
        assertEquals("old wal", File(legacy, "latch_database-wal").readText())

        // Once the file is free, the next start moves the set together.
        LegacyDataMigration.migrate(legacy, current)
        for (name in listOf("latch_database", "latch_database-wal", "latch_database-shm")) {
            assertTrue(File(current, name).exists(), "$name moved on the retry")
        }
    }

    @Test
    fun `logs and unknown files stay behind`() {
        write(legacy, "logs/latch.0.log", "log")
        write(legacy, "Latch.exe", "binary")

        val result = LegacyDataMigration.migrate(legacy, current)

        assertTrue(result.isEmpty)
        assertTrue(File(legacy, "Latch.exe").exists())
    }

    @Test
    fun `no legacy directory, or the same directory, is a no-op`() {
        legacy.deleteRecursively()
        assertTrue(LegacyDataMigration.migrate(legacy, current).isEmpty)

        current.mkdirs()
        write(current, "settings.json", "same")
        assertTrue(LegacyDataMigration.migrate(current, current).isEmpty)
        assertEquals("same", File(current, "settings.json").readText())
    }
}
