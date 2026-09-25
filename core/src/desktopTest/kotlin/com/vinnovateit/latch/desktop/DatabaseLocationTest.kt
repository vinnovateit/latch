package com.vinnovateit.latch.desktop

import com.vinnovateit.latch.core.data.Session
import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.desktop.AppPaths.DatabaseLocation
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The decision production uses to pick the database Room opens
 * ([AppPaths.chooseDatabaseLocation]), driven through real Room databases on
 * disk: whatever the legacy migration manages, a process never opens a fresh
 * database in the new directory while a legacy one is waiting to move there.
 */
class DatabaseLocationTest {
    private lateinit var root: File
    private lateinit var legacy: File
    private lateinit var current: File

    private val walLocked: (Path, Path) -> Unit = { source, target ->
        if (source.fileName.toString() == "latch_database-wal") throw IOException("held open by a scanner")
        Files.move(source, target)
    }

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("latch-db-location-").toFile()
        legacy = File(root, "Latch").apply { mkdirs() }
        current = File(File(root, "VinnovateIT"), "Latch").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A pre-1.4.3 install's database: one recorded session, plus journal files beside it. */
    private fun legacyDatabaseWithHistory(): Long = runBlocking {
        val db = buildDatabase(DatabaseLocation.OnDisk(File(legacy, "latch_database")))
        db.statsDao().insertSession(Session(startTime = 1_000, endTime = 2_000, rxBytes = 42, txBytes = 7, maxRxBps = 1, maxTxBps = 1))
        db.close()
        // An empty journal pair: present, so the set really is three files.
        File(legacy, "latch_database-wal").createNewFile()
        File(legacy, "latch_database-shm").createNewFile()
        42L
    }

    private fun openAndReadRxBytes(location: DatabaseLocation): List<Long> = runBlocking {
        val db = buildDatabase(location)
        try {
            db.statsDao().getAllSessions().first().map { it.rxBytes }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a failed journal move opens the legacy database and creates nothing in the new directory`() {
        val rx = legacyDatabaseWithHistory()

        val (location, result) = AppPaths.chooseDatabaseLocation(legacy, current, walLocked)

        assertEquals(DatabaseState.RETRY_WITH_LEGACY, result?.state)
        assertEquals(DatabaseLocation.OnDisk(File(legacy, "latch_database")), location)
        assertEquals(listOf(rx), openAndReadRxBytes(location), "this run sees the existing history")
        assertFalse(File(current, "latch_database").exists(), "no fresh database may appear in the new directory")
    }

    @Test
    fun `the next start moves the set and then opens the same history in the new directory`() {
        val rx = legacyDatabaseWithHistory()
        // Start 1: the journal is held open.
        val (first, _) = AppPaths.chooseDatabaseLocation(legacy, current, walLocked)
        openAndReadRxBytes(first)

        // Start 2: it is free again.
        val (second, result) = AppPaths.chooseDatabaseLocation(legacy, current)

        assertEquals(DatabaseState.COMPLETE, result?.state)
        assertEquals(DatabaseLocation.OnDisk(File(current, "latch_database")), second)
        assertEquals(listOf(rx), openAndReadRxBytes(second), "the migrated database is the original one")
        assertFalse(File(legacy, "latch_database").exists())

        // Start 3: nothing left to move; the new directory is authoritative.
        val (third, again) = AppPaths.chooseDatabaseLocation(legacy, current)
        assertEquals(DatabaseState.NONE, again?.state)
        assertEquals(second, third)
    }

    @Test
    fun `a split set opens no database file at all`() {
        legacyDatabaseWithHistory()
        // An earlier interrupted run moved the journals; the database cannot move now.
        Files.move(File(legacy, "latch_database-wal").toPath(), File(current, "latch_database-wal").toPath())
        Files.move(File(legacy, "latch_database-shm").toPath(), File(current, "latch_database-shm").toPath())
        val dbLocked: (Path, Path) -> Unit = { source, target ->
            if (source.fileName.toString() == "latch_database") throw IOException("held open")
            Files.move(source, target)
        }
        val before = File(legacy, "latch_database").readBytes()

        val (location, result) = AppPaths.chooseDatabaseLocation(legacy, current, dbLocked)

        assertEquals(DatabaseState.BLOCKED, result?.state)
        assertIs<DatabaseLocation.InMemory>(location)
        assertEquals(emptyList(), openAndReadRxBytes(location), "the run works, on an empty in-memory database")
        assertFalse(File(current, "latch_database").exists(), "no fresh database beside the moved journals")
        assertTrue(before.contentEquals(File(legacy, "latch_database").readBytes()), "the legacy database is untouched")

        // Once it can move, the set is whole in the new directory with its history.
        val (retry, retried) = AppPaths.chooseDatabaseLocation(legacy, current)
        assertEquals(DatabaseState.COMPLETE, retried?.state)
        assertEquals(listOf(42L), openAndReadRxBytes(retry))
    }

    @Test
    fun `an existing database in the new directory stays authoritative and uncontaminated`() {
        legacyDatabaseWithHistory()
        runBlocking {
            val live = buildDatabase(DatabaseLocation.OnDisk(File(current, "latch_database")))
            live.statsDao().insertSession(Session(startTime = 5_000, endTime = 6_000, rxBytes = 99, txBytes = 1, maxRxBps = 1, maxTxBps = 1))
            live.close()
        }

        val (location, result) = AppPaths.chooseDatabaseLocation(legacy, current)

        assertEquals(DatabaseState.DESTINATION_ALREADY_LIVE, result?.state)
        assertEquals(DatabaseLocation.OnDisk(File(current, "latch_database")), location)
        assertEquals(listOf(99L), openAndReadRxBytes(location))
        assertTrue(File(legacy, "latch_database").exists(), "legacy files are left, not joined")
    }

    @Test
    fun `without a legacy directory to consider, the new database is used`() {
        val (location, result) = AppPaths.chooseDatabaseLocation(null, current)

        assertEquals(null, result)
        assertEquals(DatabaseLocation.OnDisk(File(current, "latch_database")), location)
    }
}
