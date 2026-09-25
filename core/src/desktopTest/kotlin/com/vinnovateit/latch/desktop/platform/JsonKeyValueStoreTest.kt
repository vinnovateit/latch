package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.NoOpLogger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonKeyValueStoreTest {
    @Test
    fun `a write is on disk by the time it returns`() = withStoreFile { file ->
        val store = JsonKeyValueStore(file, NoOpLogger)

        store.putStringSet("allowed_ssids", setOf("G-VIT"))
        store.putBoolean("auto_login", false)

        assertTrue(file.exists(), "settings were not on disk when the write returned")
        // A fresh store is what the next `latch-cli` invocation sees.
        val reopened = JsonKeyValueStore(file, NoOpLogger)
        assertEquals(setOf("G-VIT"), reopened.getStringSet("allowed_ssids", setOf("VIT")))
        assertEquals(false, reopened.getBoolean("auto_login", true))
    }

    /**
     * The store used to rewrite the file in place from a background coroutine,
     * so a reader arriving mid-write parsed a truncated file, swallowed the
     * error and silently fell back to defaults -- losing the user's settings
     * for that process. Writes now land through a temp file and an atomic move,
     * so a reader sees either the old file or the new one.
     */
    @Test
    fun `a reader never sees a half-written file`() = withStoreFile { file ->
        val store = JsonKeyValueStore(file, NoOpLogger)
        store.putString("theme", "value-0")

        val failures = mutableListOf<String>()
        val writer = thread {
            repeat(400) { store.putString("theme", "value-$it") }
        }
        val reader = thread {
            repeat(400) {
                val seen = JsonKeyValueStore(file, NoOpLogger).getString("theme", "MISSING")
                if (!seen.startsWith("value-")) synchronized(failures) { failures += seen }
            }
        }
        writer.join()
        reader.join()

        assertEquals(emptyList(), failures, "reader observed a torn or missing settings file")
    }

    /**
     * Settings keep a non-atomic fallback on purpose (see JsonKeyValueStore.write),
     * which is only acceptable because a torn file is recoverable: it reads as
     * defaults rather than failing startup, and the next change rewrites it whole.
     */
    @Test
    fun `a torn settings file reads as defaults and the next change repairs it`() = withStoreFile { file ->
        JsonKeyValueStore(file, NoOpLogger).putString("theme", "Dark")
        val intact = file.readText()
        file.writeText(intact.substring(0, intact.length / 2))

        val recovered = JsonKeyValueStore(file, NoOpLogger)
        assertEquals("System Default", recovered.getString("theme", "System Default"))

        recovered.putString("accent_color", "Blue")

        val reopened = JsonKeyValueStore(file, NoOpLogger)
        assertEquals("Blue", reopened.getString("accent_color", "Red"))
        assertEquals("System Default", reopened.getString("theme", "System Default"))
    }

    private fun withStoreFile(block: (java.io.File) -> Unit) {
        val directory = createTempDirectory("latch-settings-").toFile()
        try {
            block(directory.resolve("settings.json"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
