package com.example.voicevox.core.repository

import com.example.voicevox.core.storage.KeyValueStoreFactory
import com.example.voicevox.core.storage.StoreNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * アプリ全体の設定。キー名は Android 版の AppPrefs と同じ。
 */
class SettingsRepository(factory: KeyValueStoreFactory) {
    private val store = factory.store(StoreNames.APP)

    // --- HUD ---
    var userName: String
        get() = store.getString(KEY_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME
        set(value) = store.putString(KEY_USER_NAME, value.ifBlank { DEFAULT_USER_NAME })

    var showPlayerLevel: Boolean
        get() = store.getBoolean(KEY_SHOW_PLAYER_LEVEL, true)
        set(value) = store.putBoolean(KEY_SHOW_PLAYER_LEVEL, value)

    var showCharacterLevel: Boolean
        get() = store.getBoolean(KEY_SHOW_CHAR_LEVEL, true)
        set(value) = store.putBoolean(KEY_SHOW_CHAR_LEVEL, value)

    // --- 通知 ---
    /** 0時に、重要予定へのアラームが未設定なら通知する。 */
    var mandatoryReminder: Boolean
        get() = store.getBoolean(KEY_MANDATORY_REMINDER, true)
        set(value) = store.putBoolean(KEY_MANDATORY_REMINDER, value)

    /** タスク締切の1時間前に通知する。 */
    var taskNotification: Boolean
        get() = store.getBoolean(KEY_TASK_NOTIFICATION, true)
        set(value) = store.putBoolean(KEY_TASK_NOTIFICATION, value)

    /** 予定の10分前に通知する。 */
    var eventNotification: Boolean
        get() = store.getBoolean(KEY_EVENT_NOTIFICATION, true)
        set(value) = store.putBoolean(KEY_EVENT_NOTIFICATION, value)

    // --- アラーム詳細 ---
    /**
     * 祝日はアラームを鳴らさない。
     *
     * Android 版では設定画面に項目だけあって判定が繋がっていなかったので、
     * こちらでは [com.example.voicevox.core.alarm.AlarmPlanner] が実際に見ている。
     */
    var skipHolidays: Boolean
        get() = store.getBoolean(KEY_SKIP_HOLIDAYS, false)
        set(value) = store.putBoolean(KEY_SKIP_HOLIDAYS, value)

    /** 長期休暇モード。ONの間すべてのアラームを止める。 */
    var vacationMode: Boolean
        get() = store.getBoolean(KEY_VACATION_MODE, false)
        set(value) = store.putBoolean(KEY_VACATION_MODE, value)

    // --- カレンダー ---
    var syncDeviceCalendar: Boolean
        get() = store.getBoolean(KEY_SYNC_DEVICE_CALENDAR, false)
        set(value) = store.putBoolean(KEY_SYNC_DEVICE_CALENDAR, value)

    /**
     * 同期対象に選んだ端末カレンダーの識別子。空なら全部。
     *
     * Android の設定画面は CalendarContract の数値IDを `[12,34]` として書き、
     * iOS は EventKit の文字列IDを書く。既存ユーザーの選択を落とさないよう、
     * 読み出しはどちらの表現も受け付ける(空リストは「全カレンダー」を意味するため、
     * パースに失敗して空に倒れると選択が無かったことになってしまう)。
     */
    var selectedCalendarIds: List<String>
        get() {
            val raw = store.getString(KEY_SELECTED_CALENDAR_IDS) ?: return emptyList()
            return runCatching {
                Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
            }.getOrElse { emptyList() }
        }
        set(value) = store.putString(KEY_SELECTED_CALENDAR_IDS, Json.encodeToString<List<String>>(value))

    // --- 通信 ---
    /** モデル取得時の Wi-Fi 警告を出さない。 */
    var skipWifiWarning: Boolean
        get() = store.getBoolean(KEY_SKIP_WIFI_WARNING, false)
        set(value) = store.putBoolean(KEY_SKIP_WIFI_WARNING, value)

    /** 最後に選んだ話者(VOICEVOX のスタイルID)。 */
    var lastSpeakerId: Int
        get() = store.getInt(KEY_LAST_SPEAKER_ID, DEFAULT_SPEAKER_ID)
        set(value) = store.putInt(KEY_LAST_SPEAKER_ID, value)

    var tutorialCompleted: Boolean
        get() = store.getBoolean(KEY_TUTORIAL_COMPLETED, false)
        set(value) = store.putBoolean(KEY_TUTORIAL_COMPLETED, value)

    companion object {
        const val DEFAULT_USER_NAME = "PLAYER"

        /** ずんだもん(ノーマル)。Android 版のデフォルトと同じ。 */
        const val DEFAULT_SPEAKER_ID = 3

        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SHOW_PLAYER_LEVEL = "show_player_level"
        private const val KEY_SHOW_CHAR_LEVEL = "show_char_level"
        private const val KEY_MANDATORY_REMINDER = "mandatory_reminder"
        private const val KEY_TASK_NOTIFICATION = "task_notification"
        private const val KEY_EVENT_NOTIFICATION = "event_notification"
        private const val KEY_SKIP_HOLIDAYS = "skip_holidays"
        private const val KEY_VACATION_MODE = "vacation_mode"
        private const val KEY_SYNC_DEVICE_CALENDAR = "sync_device_calendar"
        private const val KEY_SELECTED_CALENDAR_IDS = "selected_calendar_ids"
        private const val KEY_SKIP_WIFI_WARNING = "skip_wifi_warning"
        private const val KEY_LAST_SPEAKER_ID = "last_speaker_id"
        private const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"
    }
}
