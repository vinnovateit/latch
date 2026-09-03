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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.*

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

    private val scope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeChannel = Channel<JsonObject>(10)
    // Channel's buffer has size 10.

    /** Serialises the two write paths -- the writer coroutine and [flush]. */
    private val writeLock = Any()

    init {
        load()
        // Load file then launch writer coroutine.
        scope.launch {
            for (jsonObj in writeChannel) {
                write(jsonObj)
                logger.d(TAG, "Saved settings to file.")
            }
        }
    }

    private fun write(obj: JsonObject) = synchronized(writeLock) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), obj))
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
            if (!writeChannel.trySend(snapshot()).isSuccess) {
                throw Exception("Write channel is currently full.")
            }
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to persist settings", e)
        }
    }

    /**
     * Writes the current settings on the calling thread.
     *
     * The writer coroutine above is right for the desktop app, which outlives
     * any queued write by hours. It is wrong for a one-shot `latch-cli
     * --settings set ...`, which returns success and exits before the
     * coroutine is ever scheduled -- the setting was silently lost. Called from
     * DesktopEngineRuntime.close(), so every owner lands its writes on the way
     * out.
     */
    override fun flush() {
        try {
            write(snapshot())
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to flush settings", e)
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
