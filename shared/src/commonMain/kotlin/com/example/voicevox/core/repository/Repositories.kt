package com.example.voicevox.core.repository

import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.model.AlarmItem
import com.example.voicevox.core.model.AttendanceStatus
import com.example.voicevox.core.model.CalendarSource
import com.example.voicevox.core.model.EventPreset
import com.example.voicevox.core.model.IcsEvent
import com.example.voicevox.core.model.JsonKeys
import com.example.voicevox.core.model.LevelInfo
import com.example.voicevox.core.model.ScheduleEvent
import com.example.voicevox.core.model.SubjectStats
import com.example.voicevox.core.model.TaskItem
import com.example.voicevox.core.storage.KeyValueStore
import com.example.voicevox.core.storage.KeyValueStoreFactory
import com.example.voicevox.core.storage.StoreNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 保存フォーマットは Android 版 Cura の SharedPreferences + JSON をそのまま踏襲している。
 * 将来 Android 版とデータを行き来させられるようにしておくため。
 */
internal val CuraJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    // Android 版は linkedEventId を書かないことがあるので、欠けていても読めるようにする
    explicitNulls = false
}

/** JSON 配列を1つのキーに丸ごと入れる、という Android 版のやり方をなぞる基底。 */
internal abstract class JsonListRepository<T>(
    private val store: KeyValueStore,
    private val key: String,
) {
    protected abstract fun decode(raw: String): List<T>
    protected abstract fun encode(items: List<T>): String

    fun load(): List<T> {
        val raw = store.getString(key) ?: return emptyList()
        return try {
            decode(raw)
        } catch (e: Exception) {
            // 壊れた保存データでアプリごと落とさない。Android 版も握り潰している。
            emptyList()
        }
    }

    fun save(items: List<T>) = store.putString(key, encode(items))
}

class AlarmRepository(factory: KeyValueStoreFactory) {
    private val store = factory.store(StoreNames.ALARM)
    private val delegate = object : JsonListRepository<AlarmItem>(store, JsonKeys.ALARM_LIST) {
        override fun decode(raw: String) = CuraJson.decodeFromString<List<AlarmItem>>(raw)
        override fun encode(items: List<AlarmItem>) = CuraJson.encodeToString<List<AlarmItem>>(items)
    }

    /** 時刻順。 */
    fun all(): List<AlarmItem> = delegate.load().sortedWith(compareBy({ it.hour }, { it.minute }))

    fun find(id: String): AlarmItem? = all().firstOrNull { it.id == id }

    fun save(items: List<AlarmItem>) = delegate.save(items)

    fun upsert(item: AlarmItem) {
        val list = delegate.load().filterNot { it.id == item.id } + item
        save(list.sortedWith(compareBy({ it.hour }, { it.minute })))
    }

    fun delete(id: String) = save(delegate.load().filterNot { it.id == id })

    /**
     * 同じ時刻・同じ話者のアラーム。Android 版は新規作成時にこれを置き換えていた。
     */
    fun duplicateOf(hour: Int, minute: Int, speakerId: Int): AlarmItem? =
        delegate.load().firstOrNull { it.hour == hour && it.minute == minute && it.speakerId == speakerId }

    /** 有効なアラームのうち、今から見て一番早く鳴るもの。 */
    fun nextEnabled(fromMillis: Long = CuraTime.nowMillis()): AlarmItem? =
        all().filter { it.isEnabled }.minByOrNull { it.nextTriggerMillis(fromMillis) }
}

class TaskRepository(factory: KeyValueStoreFactory) {
    private val store = factory.store(StoreNames.TODO)
    private val delegate = object : JsonListRepository<TaskItem>(store, JsonKeys.TASK_LIST) {
        override fun decode(raw: String) = CuraJson.decodeFromString<List<TaskItem>>(raw)
        override fun encode(items: List<TaskItem>) = CuraJson.encodeToString<List<TaskItem>>(items)
    }

    /** 未完了を優先度の高い順、その後に完了済みを締切順で並べる。 */
    fun all(nowMillis: Long = CuraTime.nowMillis()): List<TaskItem> =
        delegate.load().sortedWith(
            compareBy({ it.isCompleted }, { -it.currentPriority(nowMillis) }, { it.deadlineMillis })
        )

    fun save(items: List<TaskItem>) = delegate.save(items)

    fun upsert(item: TaskItem) = save(delegate.load().filterNot { it.id == item.id } + item)

    fun delete(id: String) = save(delegate.load().filterNot { it.id == id })

    fun pending(): List<TaskItem> = delegate.load().filterNot { it.isCompleted }

    /** その日が締切の未完了タスクのタイトル。アラームの読み上げに使う。 */
    fun titlesDueOn(millis: Long): List<String> =
        delegate.load()
            .filterNot { it.isCompleted }
            .filter { CuraTime.isSameDay(it.deadlineMillis, millis) }
            .map { it.title }

    /** 実効優先度が 5 のタスクがあるか。キャラクターのセリフ分岐に使う。 */
    fun hasUrgentTasks(nowMillis: Long = CuraTime.nowMillis()): Boolean =
        delegate.load().any { !it.isCompleted && it.currentPriority(nowMillis) == 5 }
}

class ScheduleRepository(private val factory: KeyValueStoreFactory) {
    private val scheduleStore = factory.store(StoreNames.SCHEDULE)
    private val timetableStore = factory.store(StoreNames.TIMETABLE)

    private val events = object : JsonListRepository<ScheduleEvent>(scheduleStore, JsonKeys.EVENT_LIST) {
        override fun decode(raw: String) = CuraJson.decodeFromString<List<ScheduleEvent>>(raw)
        override fun encode(items: List<ScheduleEvent>) = CuraJson.encodeToString<List<ScheduleEvent>>(items)
    }
    private val presets = object : JsonListRepository<EventPreset>(scheduleStore, JsonKeys.PRESET_LIST) {
        override fun decode(raw: String) = CuraJson.decodeFromString<List<EventPreset>>(raw)
        override fun encode(items: List<EventPreset>) = CuraJson.encodeToString<List<EventPreset>>(items)
    }
    private val icsCache = object : JsonListRepository<IcsEvent>(timetableStore, JsonKeys.ICS_CACHE) {
        override fun decode(raw: String) = CuraJson.decodeFromString<List<IcsEvent>>(raw)
        override fun encode(items: List<IcsEvent>) = CuraJson.encodeToString<List<IcsEvent>>(items)
    }
    private val sources = object : JsonListRepository<CalendarSource>(timetableStore, JsonKeys.CALENDAR_SOURCES) {
        override fun decode(raw: String) = CuraJson.decodeFromString<List<CalendarSource>>(raw)
        override fun encode(items: List<CalendarSource>) = CuraJson.encodeToString<List<CalendarSource>>(items)
    }

    // --- 手動で追加した予定 ---
    fun customEvents(): List<ScheduleEvent> = events.load()
    fun saveCustomEvents(items: List<ScheduleEvent>) = events.save(items)
    fun upsertCustomEvent(event: ScheduleEvent) =
        events.save(events.load().filterNot { it.id == event.id } + event)

    fun deleteCustomEvent(id: String) = events.save(events.load().filterNot { it.id == id })

    // --- プリセット ---
    fun presets(): List<EventPreset> = presets.load()
    fun savePresets(items: List<EventPreset>) = presets.save(items)
    fun addPreset(preset: EventPreset) =
        presets.save(presets.load().filterNot { it.genre == preset.genre } + preset)

    fun deletePreset(genre: String) = presets.save(presets.load().filterNot { it.genre == genre })

    // --- 外部 iCal ---
    fun icsEvents(): List<IcsEvent> = icsCache.load()
    fun saveIcsEvents(items: List<IcsEvent>) = icsCache.save(items)

    fun calendarSources(): List<CalendarSource> = sources.load()
    fun saveCalendarSources(items: List<CalendarSource>) = sources.save(items)
    fun addCalendarSource(source: CalendarSource) =
        sources.save(sources.load().filterNot { it.url == source.url } + source)

    fun deleteCalendarSource(url: String) = sources.save(sources.load().filterNot { it.url == url })
}

/**
 * 出欠。科目ごとの追跡フラグと、日付ごとの状態を個別のキーに持つ。
 * Android 版と同じキー名(`track_<科目>` / `status_<科目>_<日付>` / `absent_<科目>`)。
 */
class AttendanceRepository(factory: KeyValueStoreFactory) {
    private val store = factory.store(StoreNames.ATTENDANCE)

    fun isTracked(subject: String): Boolean = store.getBoolean("track_$subject", false)

    fun setTracked(subject: String, tracked: Boolean) = store.putBoolean("track_$subject", tracked)

    fun status(subject: String, dayKey: String): AttendanceStatus =
        AttendanceStatus.parse(store.getString("status_${subject}_$dayKey", AttendanceStatus.NONE.name))

    fun setStatus(subject: String, dayKey: String, status: AttendanceStatus) =
        store.putString("status_${subject}_$dayKey", status.name)

    /** 手動で加算した欠席回数。集計値がこれを下回る場合はこちらを採用する。 */
    fun manualAbsent(subject: String): Int = store.getInt("absent_$subject", 0)

    fun setManualAbsent(subject: String, count: Int) =
        store.putInt("absent_$subject", count.coerceAtLeast(0))

    /** 追跡中の科目名。 */
    fun trackedSubjects(): List<String> =
        store.keys().filter { it.startsWith("track_") && store.getBoolean(it, false) }
            .map { it.removePrefix("track_") }
            .sorted()
}

/** プレイヤーとキャラクター(キュラ)の経験値。 */
class PlayerRepository(factory: KeyValueStoreFactory) {
    private val playerStore = factory.store(StoreNames.PLAYER)
    private val characterStore = factory.store(StoreNames.CHARACTER)

    var playerExp: Long
        get() = playerStore.getLong("totalExp", 0L)
        set(value) = playerStore.putLong("totalExp", value)

    var characterExp: Long
        get() = characterStore.getLong("totalExp", 0L)
        set(value) = characterStore.putLong("totalExp", value)

    /** キャラクターとのやりとりの累計回数。300回で追憶が解放される。 */
    var interactionCount: Long
        get() = characterStore.getLong("cumulativeInteractionCount", 0L)
        set(value) = characterStore.putLong("cumulativeInteractionCount", value)

    var memoryUnlocked: Boolean
        get() = characterStore.getBoolean("memory_unlocked", false)
        set(value) = characterStore.putBoolean("memory_unlocked", value)

    /** 最後に再生したストーリーのレベル。 */
    var lastSeenStoryLevel: Int
        get() = characterStore.getInt("last_seen_story_lv", 0)
        set(value) = characterStore.putInt("last_seen_story_lv", value)

    /** プレイヤーとキャラクターの両方に経験値を加算する。 */
    fun addExp(amount: Long) {
        if (amount <= 0) return
        playerExp += amount
        characterExp += amount
    }

    val playerLevel: LevelInfo get() = levelOf(playerExp)
    val characterLevel: LevelInfo get() = levelOf(characterExp)

    companion object {
        const val EXP_PER_LEVEL = 100L

        /** 100EXP ごとに1レベル。Lv.1 始まり。 */
        fun levelOf(exp: Long): LevelInfo {
            val level = (exp / EXP_PER_LEVEL).toInt() + 1
            return LevelInfo(level, exp - (level - 1) * EXP_PER_LEVEL, EXP_PER_LEVEL)
        }
    }
}

/** 出欠の集計結果。SubjectStats の生成は AttendanceService が行う。 */
typealias AttendanceSummary = List<SubjectStats>
