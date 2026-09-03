package com.vinnovateit.latch.core.platform

/**
 * Minimal persistent key-value storage. Mirrors the subset of Android's
 * SharedPreferences that the app actually uses, so [com.vinnovateit.latch.core.settings.SettingsManager]
 * can keep its exact API shape and key names.
 */
interface KeyValueStore {
    fun getString(key: String, default: String): String
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getStringSet(key: String, default: Set<String>): Set<String>

    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putStringSet(key: String, value: Set<String>)

    /**
     * Makes every write so far durable before the process may exit.
     *
     * A store that writes synchronously has nothing to do here. One that defers
     * writes must land them now: a `latch-cli --settings set ...` process exits
     * within milliseconds of the write, far sooner than any background writer.
     */
    fun flush() {}
}

/** Used before a real store is installed, and in tests. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, Any>()

    override fun getString(key: String, default: String): String =
        map[key] as? String ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        map[key] as? Boolean ?: default

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        map[key] as? Set<String> ?: default

    override fun putString(key: String, value: String) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun putStringSet(key: String, value: Set<String>) { map[key] = value }
}
