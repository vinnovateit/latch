package com.vinnovateit.latch.desktop

import com.vinnovateit.latch.desktop.LegacyDataMigration.DatabaseState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
 * Which database a process then opens is covered by [DatabaseLocationTest].
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

    private fun write(dir: File, name: String, text: String = "old $name") = File(dir, name).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun databaseSet(dir: File) =
        listOf("latch_database", "latch_database-wal", "latch_database-shm").forEach { write(dir, it) }

    /** A rename that fails for the named file (held open by a scanner, say), in either direction. */
    private fun failingFor(vararg names: String): (Path, Path) -> Unit = { source, target ->
        if (source.fileName.toString() in names) throw IOException("${source.fileName} is held open")
        Files.move(source, target)
    }

    private fun namesIn(dir: File) = dir.list().orEmpty().filter { it.startsWith("latch_database") }.sorted()

    // --- settings and credentials -----------------------------------------------

    @Test
    fun `settings and credentials move to the new directory intact`() {
        write(legacy, "credentials.bin")
        write(legacy, "settings.json")

        val result = LegacyDataMigration.migrate(legacy, current)

        assertEquals(listOf("credentials.bin", "settings.json"), result.moved)
        for (name in result.moved) {
            assertEquals("old $name", File(current, name).readText())
            assertFalse(File(legacy, name).exists(), "$name must be moved, not copied")
        }
    }

    @Test
    fun `a second run moves nothing`() {
        write(legacy, "settings.json")
        LegacyDataMigration.migrate(legacy, current)

        assertTrue(LegacyDataMigration.migrate(legacy, current).isEmpty)
        assertEquals("old settings.json", File(current, "settings.json").readText())
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
    fun `settings still move while the database set cannot`() {
        write(legacy, "settings.json")
        databaseSet(legacy)

        assertEquals(listOf("settings.json"), LegacyDataMigration.migrate(legacy, current).moved)
        val database = LegacyDataMigration.migrateDatabase(legacy, current, failingFor("latch_database-wal"))

        assertEquals(DatabaseState.RETRY_WITH_LEGACY, database.state)
        assertTrue(File(current, "settings.json").exists())
    }

    @Test
    fun `logs and unknown files stay behind`() {
        write(legacy, "logs/latch.0.log", "log")
        write(legacy, "Latch.exe", "binary")

        assertTrue(LegacyDataMigration.migrate(legacy, current).isEmpty)
        assertEquals(DatabaseState.NONE, LegacyDataMigration.migrateDatabase(legacy, current).state)
        assertTrue(File(legacy, "Latch.exe").exists())
    }

    // --- the database set -------------------------------------------------------

    @Test
    fun `the whole database set moves and the migration is complete`() {
        databaseSet(legacy)

        val result = LegacyDataMigration.migrateDatabase(legacy, current)

        assertEquals(DatabaseState.COMPLETE, result.state)
        assertEquals(listOf("latch_database-wal", "latch_database-shm", "latch_database"), result.moved)
        assertEquals(listOf("latch_database", "latch_database-shm", "latch_database-wal"), namesIn(current))
        assertEquals(emptyList(), namesIn(legacy))
    }

    @Test
    fun `a journal that cannot be moved keeps the whole set in the old place`() {
        databaseSet(legacy)

        val result = LegacyDataMigration.migrateDatabase(legacy, current, failingFor("latch_database-wal"))

        assertEquals(DatabaseState.RETRY_WITH_LEGACY, result.state)
        assertEquals(emptyList(), namesIn(current), "nothing of the set may sit in the new place")
        assertEquals(listOf("latch_database", "latch_database-shm", "latch_database-wal"), namesIn(legacy))
    }

    @Test
    fun `a later file that cannot be moved brings the earlier ones back`() {
        // The -wal moves, then the -shm fails: the -wal is renamed back.
        databaseSet(legacy)

        val shmLocked = LegacyDataMigration.migrateDatabase(legacy, current, failingFor("latch_database-shm"))

        assertEquals(DatabaseState.RETRY_WITH_LEGACY, shmLocked.state)
        assertEquals(emptyList(), namesIn(current))
        assertEquals("old latch_database-wal", File(legacy, "latch_database-wal").readText())

        // The database itself locked: both journals are brought back.
        val dbLocked = LegacyDataMigration.migrateDatabase(legacy, current, failingFor("latch_database"))

        assertEquals(DatabaseState.RETRY_WITH_LEGACY, dbLocked.state)
        assertEquals(emptyList(), namesIn(current))
        assertEquals(listOf("latch_database", "latch_database-shm", "latch_database-wal"), namesIn(legacy))
    }

    @Test
    fun `a set that cannot be brought back together is blocked`() {
        // The database fails, and so does moving the -wal back: the set is split.
        databaseSet(legacy)
        var walMoved = false
        val rename: (Path, Path) -> Unit = { source, target ->
            val name = source.fileName.toString()
            when {
                name == "latch_database" -> throw IOException("database held open")
                name == "latch_database-wal" && walMoved -> throw IOException("journal held open")
                else -> {
                    Files.move(source, target)
                    if (name == "latch_database-wal") walMoved = true
                }
            }
        }

        val result = LegacyDataMigration.migrateDatabase(legacy, current, rename)

        assertEquals(DatabaseState.BLOCKED, result.state)
        assertTrue(File(current, "latch_database-wal").exists())
        assertTrue(File(legacy, "latch_database").exists())
    }

    @Test
    fun `an interrupted earlier run is completed`() {
        // The -wal moved last time, the rest did not.
        write(current, "latch_database-wal")
        write(legacy, "latch_database-shm")
        write(legacy, "latch_database")

        assertEquals(DatabaseState.COMPLETE, LegacyDataMigration.migrateDatabase(legacy, current).state)
        assertEquals(listOf("latch_database", "latch_database-shm", "latch_database-wal"), namesIn(current))
        assertEquals("old latch_database-wal", File(current, "latch_database-wal").readText())
    }

    @Test
    fun `an interrupted earlier run that still cannot finish is blocked, not opened half`() {
        // -wal and -shm moved last time; the database still cannot move.
        write(current, "latch_database-wal")
        write(current, "latch_database-shm")
        write(legacy, "latch_database")

        val result = LegacyDataMigration.migrateDatabase(legacy, current, failingFor("latch_database"))

        assertEquals(DatabaseState.BLOCKED, result.state)
        assertTrue(File(legacy, "latch_database").exists())
        assertFalse(File(current, "latch_database").exists())
    }

    @Test
    fun `a journal in both places is a conflict, not a merge`() {
        write(current, "latch_database-wal", "journal already moved")
        databaseSet(legacy)

        val result = LegacyDataMigration.migrateDatabase(legacy, current)

        assertEquals(DatabaseState.BLOCKED, result.state)
        assertEquals("journal already moved", File(current, "latch_database-wal").readText())
        assertTrue(File(legacy, "latch_database").exists())
    }

    @Test
    fun `an old journal never joins a database already in the new directory`() {
        databaseSet(legacy)
        write(current, "latch_database", "live db")

        val result = LegacyDataMigration.migrateDatabase(legacy, current)

        assertEquals(DatabaseState.DESTINATION_ALREADY_LIVE, result.state)
        assertEquals(listOf("latch_database"), namesIn(current))
        assertEquals("live db", File(current, "latch_database").readText())
        assertEquals(listOf("latch_database", "latch_database-shm", "latch_database-wal"), namesIn(legacy))
    }

    @Test
    fun `journals without their database are left alone`() {
        write(legacy, "latch_database-wal")
        write(legacy, "latch_database-shm")

        assertEquals(DatabaseState.NONE, LegacyDataMigration.migrateDatabase(legacy, current).state)
        assertEquals(emptyList(), namesIn(current))
        assertEquals(listOf("latch_database-shm", "latch_database-wal"), namesIn(legacy))
    }

    @Test
    fun `no legacy directory, or the same directory, is a no-op`() {
        legacy.deleteRecursively()
        assertTrue(LegacyDataMigration.migrate(legacy, current).isEmpty)
        assertEquals(DatabaseState.NONE, LegacyDataMigration.migrateDatabase(legacy, current).state)

        current.mkdirs()
        write(current, "latch_database", "same")
        assertTrue(LegacyDataMigration.migrate(current, current).isEmpty)
        assertEquals(DatabaseState.NONE, LegacyDataMigration.migrateDatabase(current, current).state)
        assertEquals("same", File(current, "latch_database").readText())
    }
}
