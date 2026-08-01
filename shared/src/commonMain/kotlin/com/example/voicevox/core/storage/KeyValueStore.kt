package com.example.voicevox.core.storage

/**
 * 名前付きの Key-Value ストア。
 *
 * Cura(Android) は SharedPreferences を "AppPrefs" / "AlarmPrefs" のように
 * 名前で分けて使っていた。その区分をそのまま持ち込むことで、保存フォーマットを
 * 共通ロジック側から一貫して扱えるようにしている。
 * 実装は Android が SharedPreferences、iOS が NSUserDefaults。
 */
interface KeyValueStore {
    fun getString(key: String, default: String? = null): String?
    fun putString(key: String, value: String?)

    fun getLong(key: String, default: Long = 0L): Long
    fun putLong(key: String, value: Long)

    fun getInt(key: String, default: Int = 0): Int
    fun putInt(key: String, value: Int)

    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun remove(key: String)

    /** キーの一覧。バックアップ・復元に使う。 */
    fun keys(): Set<String>
}

/** 名前から [KeyValueStore] を引くファクトリ。 */
interface KeyValueStoreFactory {
    fun store(name: String): KeyValueStore
}

/**
 * Cura が使う store の名前。Android 版の SharedPreferences 名と一致させてある。
 */
object StoreNames {
    const val APP = "AppPrefs"
    const val ALARM = "AlarmPrefs"
    const val TODO = "TodoPrefs"
    const val SCHEDULE = "SchedulePrefs"
    const val TIMETABLE = "TimetablePrefs"
    const val ATTENDANCE = "AttendancePrefs"
    const val PLAYER = "PlayerPrefs"
    const val CHARACTER = "CharacterPrefs"

    val all = listOf(APP, ALARM, TODO, SCHEDULE, TIMETABLE, ATTENDANCE, PLAYER, CHARACTER)
}
