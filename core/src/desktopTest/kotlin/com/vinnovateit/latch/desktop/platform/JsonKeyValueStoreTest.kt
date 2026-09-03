package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.NoOpLogger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonKeyValueStoreTest {
    @Test
    fun `flush lands writes a short-lived process would otherwise lose`() {
        val directory = createTempDirectory("latch-settings-").toFile()
        val file = directory.resolve("settings.json")
        try {
            val store = JsonKeyValueStore(file, NoOpLogger)

            store.putStringSet("allowed_ssids", setOf("G-VIT"))
            store.putBoolean("auto_login", false)
            // Stands in for the process exiting: no scheduling window is given
            // to the background writer.
            store.flush()

            assertTrue(file.exists(), "settings were not on disk when flush() returned")
            // A fresh store is what the next `latch-cli` invocation sees.
            val reopened = JsonKeyValueStore(file, NoOpLogger)
            assertEquals(setOf("G-VIT"), reopened.getStringSet("allowed_ssids", setOf("VIT")))
            assertEquals(false, reopened.getBoolean("auto_login", true))
        } finally {
            directory.deleteRecursively()
        }
    }
}
