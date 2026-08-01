package com.example.voicevox.core

import com.example.voicevox.core.storage.KeyValueStore
import com.example.voicevox.core.storage.KeyValueStoreFactory

class InMemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, Any?>()

    override fun getString(key: String, default: String?): String? = values[key] as? String ?: default
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { values[key] = value }

    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { values[key] = value }

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }

    override fun remove(key: String) { values.remove(key) }

    override fun keys(): Set<String> = values.keys.toSet()
}

class InMemoryStoreFactory : KeyValueStoreFactory {
    private val stores = mutableMapOf<String, KeyValueStore>()
    override fun store(name: String): KeyValueStore = stores.getOrPut(name) { InMemoryStore() }
}
