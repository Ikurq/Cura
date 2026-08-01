package com.example.voicevox.core.storage

import platform.Foundation.NSUserDefaults

/**
 * NSUserDefaults による [KeyValueStore]。
 *
 * Android の SharedPreferences が「名前ごとに別ファイル」なのに対し、
 * NSUserDefaults の標準ドメインは1つしかない。名前をキーの接頭辞にして
 * 同じ区分けを再現している(suite を分ける手もあるが、バックアップと
 * 一括削除を素直に書けるのでこちらにした)。
 */
class UserDefaultsStore(
    private val name: String,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {

    private fun scoped(key: String) = "$name.$key"

    override fun getString(key: String, default: String?): String? =
        defaults.stringForKey(scoped(key)) ?: default

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(scoped(key))
        else defaults.setObject(value, scoped(key))
    }

    override fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(scoped(key)) == null) default
        else defaults.integerForKey(scoped(key))

    override fun putLong(key: String, value: Long) = defaults.setInteger(value, scoped(key))

    override fun getInt(key: String, default: Int): Int =
        if (defaults.objectForKey(scoped(key)) == null) default
        else defaults.integerForKey(scoped(key)).toInt()

    override fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), scoped(key))

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(scoped(key)) == null) default
        else defaults.boolForKey(scoped(key))

    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, scoped(key))

    override fun remove(key: String) = defaults.removeObjectForKey(scoped(key))

    override fun keys(): Set<String> {
        val prefix = "$name."
        return defaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }
}

/** iOS 用のファクトリ。 */
class UserDefaultsStoreFactory(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStoreFactory {
    private val cache = mutableMapOf<String, KeyValueStore>()

    override fun store(name: String): KeyValueStore =
        cache.getOrPut(name) { UserDefaultsStore(name, defaults) }
}
