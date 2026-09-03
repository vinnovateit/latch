package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.KeyValueStore
import com.vinnovateit.latch.core.platform.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Settings persistence as a plain JSON file.
 *
 * Chosen over java.util.prefs.Preferences deliberately: on Windows that writes
 * into HKCU\Software\JavaSoft\Prefs, logs a warning the first time a key is
 * absent, case-mangles keys, and caps values at 8KB. A JSON file is
 * inspectable, hand-editable for support, and trivially portable to Linux/macOS.
 */
class JsonKeyValueStore(
    private val file: File,
    private val logger: Logger,
) : KeyValueStore {

    private companion object {
        const val TAG = "JsonKeyValueStore"
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val values: MutableMap<String, JsonPrimitiveOrArray> = mutableMapOf()

    private sealed interface JsonPrimitiveOrArray {
        data class Str(val value: String) : JsonPrimitiveOrArray
        data class Bool(val value: Boolean) : JsonPrimitiveOrArray
        data class StrSet(val value: Set<String>) : JsonPrimitiveOrArray
    }

    /** Serialises writers against each other. */
    private val writeLock = Any()

    init {
        load()
    }

    /**
     * Writes the settings file whole, on the calling thread.
     *
     * Deliberately not deferred to a coroutine. A background writer meant a
     * one-shot `latch-cli --settings set ...` exited before its write ran, and
     * it left a window where the file was truncated mid-rewrite -- a reader
     * arriving then (another Latch process, or the next store instance) parsed
     * nothing and silently fell back to defaults. The file is well under a
     * kilobyte and only written on an explicit settings change, so writing it
     * inline costs nothing worth deferring.
     *
     * The temp-file-plus-move keeps that window closed for readers: they see
     * either the previous file or the new one, never a half-written one.
     */
    private fun write(obj: JsonObject) = synchronized(writeLock) {
        file.parentFile?.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), obj))
            try {
                Files.move(
                    temporary,
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        logger.d(TAG, "Saved settings to file.")
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return
            root.forEach { (key, element) ->
                when (element) {
                    is JsonPrimitive -> {
                        val asBool = element.booleanOrNull
                        values[key] = if (asBool != null && !element.isString) {
                            JsonPrimitiveOrArray.Bool(asBool)
                        } else {
                            JsonPrimitiveOrArray.Str(element.content)
                        }
                    }

                    is JsonArray -> values[key] = JsonPrimitiveOrArray.StrSet(
                        element.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                    )

                    else -> Unit
                }
            }
        } catch (e: Throwable) {
            // A corrupt settings file must not stop the app from starting; fall
            // back to defaults.
            logger.e(TAG, "Settings file unreadable; using defaults", e)
        }
    }

    private fun snapshot(): JsonObject = synchronized(writeLock) {
        buildJsonObject {
            values.forEach { (key, value) ->
                when (value) {
                    is JsonPrimitiveOrArray.Str -> put(key, JsonPrimitive(value.value))
                    is JsonPrimitiveOrArray.Bool -> put(key, JsonPrimitive(value.value))
                    is JsonPrimitiveOrArray.StrSet -> put(
                        key,
                        JsonArray(value.value.map { JsonPrimitive(it) }),
                    )
                }
            }
        }
    }

    private fun persist() {
        try {
            write(snapshot())
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to persist settings", e)
        }
    }

    override fun getString(key: String, default: String): String =
        (values[key] as? JsonPrimitiveOrArray.Str)?.value ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        (values[key] as? JsonPrimitiveOrArray.Bool)?.value ?: default

    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        (values[key] as? JsonPrimitiveOrArray.StrSet)?.value ?: default

    override fun putString(key: String, value: String) {
        synchronized(writeLock) { values[key] = JsonPrimitiveOrArray.Str(value) }; persist()
    }

    override fun putBoolean(key: String, value: Boolean) {
        synchronized(writeLock) { values[key] = JsonPrimitiveOrArray.Bool(value) }; persist()
    }

    override fun putStringSet(key: String, value: Set<String>) {
        synchronized(writeLock) { values[key] = JsonPrimitiveOrArray.StrSet(value) }; persist()
    }
}
