package com.example.voicevox.core.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences による [KeyValueStore]。
 * 名前をそのまま SharedPreferences 名に使うので、Android 版 Cura の保存先と一致する。
 */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys
}

class SharedPreferencesStoreFactory(context: Context) : KeyValueStoreFactory {
    private val appContext = context.applicationContext
    private val cache = mutableMapOf<String, KeyValueStore>()

    override fun store(name: String): KeyValueStore = cache.getOrPut(name) {
        SharedPreferencesStore(appContext.getSharedPreferences(name, Context.MODE_PRIVATE))
    }
}
