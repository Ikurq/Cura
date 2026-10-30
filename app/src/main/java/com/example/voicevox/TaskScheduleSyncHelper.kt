package com.example.voicevox

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.UUID

object TaskScheduleSyncHelper {

    private const val DEFAULT_DURATION_MILLIS = 3600000L // 1時間

    /**
     * タスクの情報からカレンダー登録用の ScheduleEvent を生成する。
     */
    fun buildScheduleEvent(task: TaskItem, defaultDurationMillis: Long = DEFAULT_DURATION_MILLIS): ScheduleEvent {
        val eventId = task.linkedEventId ?: "task_event_${UUID.randomUUID()}"
        val startTime = task.getEffectiveDeadline()
        val endTime = startTime + defaultDurationMillis

        return ScheduleEvent(
            id = eventId,
            summary = task.title,
            startTime = startTime,
            location = "タスク",
            isPreset = false,
            isAttendanceTracked = false,
            attendanceStatus = "NONE",
            repeatGroupId = null,
            endTime = endTime,
            isAllDay = false
        )
    }

    /**
     * 指定された特定の日付（カレンダー選択日）にタスクを配置する ScheduleEvent を生成する。
     * 時刻はタスクのデッドラインの時・分を引き継ぐ。
     */
    fun buildScheduleEventForDate(
        task: TaskItem,
        targetDate: Calendar,
        defaultDurationMillis: Long = DEFAULT_DURATION_MILLIS
    ): ScheduleEvent {
        val deadlineCal = Calendar.getInstance().apply { timeInMillis = task.getEffectiveDeadline() }
        val startCal = (targetDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, deadlineCal.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, deadlineCal.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val eventId = task.linkedEventId ?: "task_event_${UUID.randomUUID()}"
        val startTime = startCal.timeInMillis
        val endTime = startTime + defaultDurationMillis

        return ScheduleEvent(
            id = eventId,
            summary = task.title,
            startTime = startTime,
            location = "タスク",
            isPreset = false,
            isAttendanceTracked = false,
            attendanceStatus = "NONE",
            repeatGroupId = null,
            endTime = endTime,
            isAllDay = false
        )
    }

    /**
     * 予定リストにタスク由来のイベントを追加または更新し、
     * 更新されたイベントリストと linkedEventId が設定された TaskItem を返す。
     */
    fun syncTaskToSchedule(
        eventList: List<ScheduleEvent>,
        task: TaskItem,
        defaultDurationMillis: Long = DEFAULT_DURATION_MILLIS
    ): Pair<List<ScheduleEvent>, TaskItem> {
        val event = buildScheduleEvent(task, defaultDurationMillis)
        val updatedTask = task.copy(linkedEventId = event.id)

        val mutableList = eventList.toMutableList()
        val existingIndex = mutableList.indexOfFirst { it.id == event.id }
        if (existingIndex != -1) {
            mutableList[existingIndex] = event
        } else {
            mutableList.add(event)
        }

        return Pair(mutableList, updatedTask)
    }

    /**
     * 予定リストから指定された linkedEventId のイベントを削除する。
     */
    fun removeTaskFromSchedule(eventList: List<ScheduleEvent>, linkedEventId: String): List<ScheduleEvent> {
        return eventList.filterNot { it.id == linkedEventId }
    }

    /**
     * SharedPreferences から未完了タスクの一覧を取得する。
     */
    fun getIncompleteTasks(context: Context): List<TaskItem> {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(CuraConstants.KEY_TASK_LIST, null) ?: return emptyList()
        return try {
            val allTasks = Json.decodeFromString<List<TaskItem>>(jsonStr)
            allTasks.filterNot { it.isCompleted }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * タスクをカレンダーの SharedPreferences に予定として永続化・同期する。
     * 同期後の TaskItem（linkedEventId を更新したもの）を返す。
     */
    fun saveTaskToSchedule(context: Context, task: TaskItem): TaskItem {
        val schedulePrefs = context.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val currentJson = schedulePrefs.getString(CuraConstants.KEY_EVENT_LIST, "[]") ?: "[]"
        val currentEvents = try {
            Json.decodeFromString<List<ScheduleEvent>>(currentJson)
        } catch (e: Exception) {
            emptyList()
        }

        val (updatedEvents, updatedTask) = syncTaskToSchedule(currentEvents, task)
        val newJson = Json.encodeToString(updatedEvents)
        schedulePrefs.edit().putString(CuraConstants.KEY_EVENT_LIST, newJson).apply()

        // アラームや通知の再スケジュール・ウィジェット更新のトリガー
        triggerScheduleBroadcast(context)

        return updatedTask
    }

    /**
     * カレンダーの SharedPreferences から紐付く予定を削除する。
     */
    fun removeTaskFromSchedule(context: Context, linkedEventId: String) {
        val schedulePrefs = context.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val currentJson = schedulePrefs.getString(CuraConstants.KEY_EVENT_LIST, "[]") ?: "[]"
        val currentEvents = try {
            Json.decodeFromString<List<ScheduleEvent>>(currentJson)
        } catch (e: Exception) {
            emptyList()
        }

        val updatedEvents = removeTaskFromSchedule(currentEvents, linkedEventId)
        val newJson = Json.encodeToString(updatedEvents)
        schedulePrefs.edit().putString(CuraConstants.KEY_EVENT_LIST, newJson).apply()

        triggerScheduleBroadcast(context)
    }

    /**
     * 指定されたタスクIDの linkedEventId を更新した新しいリストを返す。
     */
    fun updateTaskLinkedEventId(tasks: List<TaskItem>, taskId: String, linkedEventId: String?): List<TaskItem> {
        return tasks.map { task ->
            if (task.id == taskId) {
                task.copy(linkedEventId = linkedEventId)
            } else {
                task
            }
        }
    }

    /**
     * 指定タスクに予定IDを紐付けて SharedPreferences に永続化する。
     */
    fun linkTaskToEvent(context: Context, taskId: String, eventId: String) {
        val taskPrefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        val jsonStr = taskPrefs.getString(CuraConstants.KEY_TASK_LIST, null) ?: return
        try {
            val tasks = Json.decodeFromString<List<TaskItem>>(jsonStr)
            val updatedTasks = updateTaskLinkedEventId(tasks, taskId, eventId)
            taskPrefs.edit().putString(CuraConstants.KEY_TASK_LIST, Json.encodeToString(updatedTasks)).apply()
        } catch (e: Exception) {}
    }

    /**
     * 予定IDに紐付くタスクのリンクを解除して SharedPreferences に永続化する。
     */
    fun unlinkTaskFromEvent(context: Context, eventId: String) {
        val taskPrefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        val jsonStr = taskPrefs.getString(CuraConstants.KEY_TASK_LIST, null) ?: return
        try {
            val tasks = Json.decodeFromString<List<TaskItem>>(jsonStr)
            val updatedTasks = tasks.map { if (it.linkedEventId == eventId) it.copy(linkedEventId = null) else it }
            taskPrefs.edit().putString(CuraConstants.KEY_TASK_LIST, Json.encodeToString(updatedTasks)).apply()
        } catch (e: Exception) {}
    }

    private fun triggerScheduleBroadcast(context: Context) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "SCHEDULE_NOTIFICATIONS"
        }
        context.sendBroadcast(intent)
    }
}
