package com.example.voicevox

import java.util.Calendar

enum class TaskReminderType {
    TARGET_MORNING,       // 目標期限の当日朝 8:00
    TARGET_ONE_HOUR,      // 目標期限の1時間前
    REAL_PREV_NIGHT,      // 最終期限の前日夜 20:00
    REAL_ONE_HOUR         // 最終期限の1時間前
}

data class TaskReminder(
    val taskId: String,
    val type: TaskReminderType,
    val triggerTimeMillis: Long,
    val title: String,
    val message: String,
    val requestCode: Int
)

object TaskReminderHelper {

    fun getRequestCode(taskId: String, type: TaskReminderType): Int {
        return (taskId.hashCode() * 31 + type.ordinal).and(0x7FFFFFFF)
    }

    fun getAllRequestCodes(taskId: String): List<Int> {
        return TaskReminderType.values().map { getRequestCode(taskId, it) }
    }

    /**
     * 指定されたタスクに対する有効なリマインダーリストを計算する
     */
    fun calculateReminders(task: TaskItem, nowMillis: Long): List<TaskReminder> {
        if (task.isCompleted) return emptyList()

        val reminders = mutableListOf<TaskReminder>()

        // 1. 目標期限関連のリマインダー
        task.targetDeadlineMillis?.let { targetDeadline ->
            // 当日朝 8:00
            val targetMorning = Calendar.getInstance().apply {
                timeInMillis = targetDeadline
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            if (nowMillis < targetMorning && targetMorning < targetDeadline) {
                reminders.add(
                    TaskReminder(
                        taskId = task.id,
                        type = TaskReminderType.TARGET_MORNING,
                        triggerTimeMillis = targetMorning,
                        title = "【目標期限】本日のタスク",
                        message = "「${task.title}」の目標期限は本日です",
                        requestCode = getRequestCode(task.id, TaskReminderType.TARGET_MORNING)
                    )
                )
            }

            // 1時間前
            val targetOneHour = targetDeadline - (60 * 60 * 1000L)
            if (nowMillis < targetOneHour) {
                reminders.add(
                    TaskReminder(
                        taskId = task.id,
                        type = TaskReminderType.TARGET_ONE_HOUR,
                        triggerTimeMillis = targetOneHour,
                        title = "【目標期限】まもなく目標期限",
                        message = "「${task.title}」の目標期限まであと1時間です",
                        requestCode = getRequestCode(task.id, TaskReminderType.TARGET_ONE_HOUR)
                    )
                )
            }
        }

        // 2. 最終期限関連のリマインダー
        val realDeadline = task.deadlineMillis

        // 前日夜 20:00
        val realPrevNight = Calendar.getInstance().apply {
            timeInMillis = realDeadline
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        if (nowMillis < realPrevNight && realPrevNight < realDeadline) {
            reminders.add(
                TaskReminder(
                    taskId = task.id,
                    type = TaskReminderType.REAL_PREV_NIGHT,
                    triggerTimeMillis = realPrevNight,
                    title = "【期限前日】明日締切のタスク",
                    message = "「${task.title}」の最終期限は明日です",
                    requestCode = getRequestCode(task.id, TaskReminderType.REAL_PREV_NIGHT)
                )
            )
        }

        // 1時間前
        val realOneHour = realDeadline - (60 * 60 * 1000L)
        if (nowMillis < realOneHour) {
            reminders.add(
                TaskReminder(
                    taskId = task.id,
                    type = TaskReminderType.REAL_ONE_HOUR,
                    triggerTimeMillis = realOneHour,
                    title = "【締切直前】最終期限1時間前",
                    message = "「${task.title}」の最終期限まであと1時間です",
                    requestCode = getRequestCode(task.id, TaskReminderType.REAL_ONE_HOUR)
                )
            )
        }

        return reminders
    }
}
