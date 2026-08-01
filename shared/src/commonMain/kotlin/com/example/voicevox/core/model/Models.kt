package com.example.voicevox.core.model

import com.example.voicevox.core.core.CuraTime
import kotlinx.serialization.Serializable

/**
 * アラーム1件。
 *
 * フィールド名は Android 版の JSON(AlarmPrefs/alarmListJSON)と一致させてある。
 * `speakerId` は VOICEVOX のスタイルIDで、モデルを跨いで一意。
 */
@Serializable
data class AlarmItem(
    val id: String,
    val hour: Int,
    val minute: Int,
    val message: String,
    val speakerId: Int,
    val speakerName: String,
    val isEnabled: Boolean = true,
    val readTasks: Boolean = false,
    val vibrate: Boolean = true,
    /** 繰り返し曜日。1=日 … 7=土。空なら単発。 */
    val repeatDays: List<Int> = emptyList(),
) {
    /** 次に鳴る時刻(エポックミリ秒)。 */
    fun nextTriggerMillis(fromMillis: Long = CuraTime.nowMillis()): Long =
        CuraTime.nextOccurrence(hour, minute, repeatDays, fromMillis)

    val timeLabel: String get() = CuraTime.formatHourMinute(hour, minute)
}

/** タスク1件。TodoPrefs/taskListJSON。 */
@Serializable
data class TaskItem(
    val id: String,
    val title: String,
    val deadlineMillis: Long,
    /** 1〜5。締切が近いと [currentPriority] が自動で引き上がる。 */
    val basePriority: Int,
    val linkedEventId: String? = null,
    val isCompleted: Boolean = false,
) {
    /**
     * 表示・並び替えに使う実効優先度。
     *
     * 完了済みは 0。締切が当日以前なら 5、翌日なら 4、翌々日なら 3 に引き上げ、
     * それ以降は basePriority のまま。Android 版の getCurrentPriority と同じ規則。
     */
    fun currentPriority(nowMillis: Long = CuraTime.nowMillis()): Int {
        if (isCompleted) return 0
        val diffDays = CuraTime.dayDifference(nowMillis, deadlineMillis)
        return when {
            diffDays <= 0 -> 5
            diffDays == 1 -> 4
            diffDays == 2 -> 3
            else -> basePriority
        }.coerceIn(1, 5)
    }

    /** タスク完了で得られる経験値。低優先度をこなす方にボーナスが付く。 */
    val expReward: Long get() = basePriority * 20L + if (basePriority == 1) 50L else 0L
}

/** 出欠の記録状態。 */
enum class AttendanceStatus {
    NONE, ATTEND, ABSENT, LATE;

    companion object {
        fun parse(raw: String?): AttendanceStatus =
            entries.firstOrNull { it.name == raw } ?: NONE
    }
}

/** 手動で追加した予定。SchedulePrefs/eventListJSON。 */
@Serializable
data class ScheduleEvent(
    val id: String,
    val genre: String,
    val startTime: Long,
    val location: String = "",
    val isPreset: Boolean = false,
    val isAttendanceTracked: Boolean = false,
    val attendanceStatus: String = AttendanceStatus.NONE.name,
) {
    val status: AttendanceStatus get() = AttendanceStatus.parse(attendanceStatus)
}

/** 予定のテンプレート。SchedulePrefs/presetListJSON。 */
@Serializable
data class EventPreset(
    val genre: String,
    val location: String = "",
    /** -1 は「時刻を保存していない」。 */
    val hour: Int = -1,
    val minute: Int = -1,
)

/** iCal から取り込んだ、あるいは端末カレンダー由来の予定。 */
@Serializable
data class IcsEvent(
    val summary: String,
    val startTime: Long,
    val endTime: Long,
    val location: String = "",
    val isAttendanceTracked: Boolean = false,
    val attendanceStatus: String = AttendanceStatus.NONE.name,
)

/** 購読している iCal の配信元。TimetablePrefs/calendarSourcesJSON。 */
@Serializable
data class CalendarSource(
    val name: String,
    val url: String,
)

/** 端末カレンダー1件。iOS では EventKit のカレンダー。 */
data class DeviceCalendarInfo(
    val id: String,
    val name: String,
    val account: String,
)

/** 科目ごとの出欠集計。 */
data class SubjectStats(
    val name: String,
    val totalScheduled: Int,
    val attended: Int,
    val absent: Int,
    val late: Int,
    /** 欠席として記録された日("2026-07-27" 形式)。新しい順。 */
    val absentDates: List<String>,
)

/** ホーム画面に並べる、時刻順の1行。 */
data class ScheduleItem(
    val id: String,
    val timeLabel: String,
    val title: String,
    val subtitle: String,
    val sortTime: Long,
    val attendanceStatus: AttendanceStatus = AttendanceStatus.NONE,
    val isAttendanceTracked: Boolean = false,
)

/** レベルと次のレベルまでの進捗。 */
data class LevelInfo(
    val level: Int,
    val currentExp: Long,
    val requiredExp: Long,
) {
    val progress: Float
        get() = if (requiredExp <= 0) 0f else (currentExp.toFloat() / requiredExp.toFloat())
}

/** 音声モデルに含まれる、選択可能な声1件。 */
data class Voice(
    val styleId: Int,
    val characterName: String,
    val styleName: String,
    val modelId: String,
) {
    val displayName: String
        get() = if (styleName == "ノーマル") characterName else "$characterName（$styleName）"
}

/** 音声モデル1件。取得状況つき。 */
data class VoiceModel(
    val id: String,
    val title: String,
    val sizeBytes: Long,
    val isDownloaded: Boolean,
    val creditTexts: List<String>,
    val voices: List<Voice>,
)

/** JSON を格納しているキーの名前。Android 版と一致させるためだけに存在する。 */
internal object JsonKeys {
    const val ALARM_LIST = "alarmListJSON"
    const val TASK_LIST = "taskListJSON"
    const val EVENT_LIST = "eventListJSON"
    const val PRESET_LIST = "presetListJSON"
    const val ICS_CACHE = "icsCacheJSON"
    const val CALENDAR_SOURCES = "calendarSourcesJSON"
    const val SELECTED_CALENDAR_IDS = "selected_calendar_ids"
}
