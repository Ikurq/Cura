package com.example.voicevox

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TaskReminderHelperTest {

    private fun createTime(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun testCompletedTask_returnsNoReminders() {
        val now = createTime(2026, Calendar.OCTOBER, 23, 10)
        val deadline = createTime(2026, Calendar.OCTOBER, 25, 18)
        val task = TaskItem(
            id = "task-completed",
            title = "完了済み",
            deadlineMillis = deadline,
            basePriority = 3,
            isCompleted = true
        )

        val reminders = TaskReminderHelper.calculateReminders(task, now)
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun testRealDeadlineReminders_futureTask() {
        // 現在: 10/23 10:00, 締切: 10/24 23:59
        val now = createTime(2026, Calendar.OCTOBER, 23, 10)
        val deadline = createTime(2026, Calendar.OCTOBER, 24, 23, 59)
        val task = TaskItem(
            id = "task-1",
            title = "レポート",
            deadlineMillis = deadline,
            basePriority = 3
        )

        val reminders = TaskReminderHelper.calculateReminders(task, now)
        assertEquals(2, reminders.size)

        // 1. 前日夜 20:00 (10/23 20:00)
        val prevNight = reminders.find { it.type == TaskReminderType.REAL_PREV_NIGHT }
        assertNotNull(prevNight)
        assertEquals(createTime(2026, Calendar.OCTOBER, 23, 20), prevNight!!.triggerTimeMillis)
        assertTrue(prevNight.title.contains("明日締切"))

        // 2. 1時間前 (10/24 22:59)
        val oneHour = reminders.find { it.type == TaskReminderType.REAL_ONE_HOUR }
        assertNotNull(oneHour)
        assertEquals(createTime(2026, Calendar.OCTOBER, 24, 22, 59), oneHour!!.triggerTimeMillis)
        assertTrue(oneHour.title.contains("1時間前"))
    }

    @Test
    fun testTargetDeadlineReminders_whenPresent() {
        // 現在: 10/23 06:00, 目標: 10/24 15:00, 最終: 10/25 23:59
        val now = createTime(2026, Calendar.OCTOBER, 23, 6)
        val target = createTime(2026, Calendar.OCTOBER, 24, 15, 0)
        val deadline = createTime(2026, Calendar.OCTOBER, 25, 23, 59)

        val task = TaskItem(
            id = "task-2",
            title = "プレゼン準備",
            deadlineMillis = deadline,
            basePriority = 4,
            targetDeadlineMillis = target
        )

        val reminders = TaskReminderHelper.calculateReminders(task, now)
        assertEquals(4, reminders.size)

        // 目標当日朝 8:00 (10/24 08:00)
        val targetMorning = reminders.find { it.type == TaskReminderType.TARGET_MORNING }
        assertNotNull(targetMorning)
        assertEquals(createTime(2026, Calendar.OCTOBER, 24, 8), targetMorning!!.triggerTimeMillis)

        // 目標1時間前 (10/24 14:00)
        val targetOneHour = reminders.find { it.type == TaskReminderType.TARGET_ONE_HOUR }
        assertNotNull(targetOneHour)
        assertEquals(createTime(2026, Calendar.OCTOBER, 24, 14), targetOneHour!!.triggerTimeMillis)

        // 最終前日夜 20:00 (10/24 20:00)
        val realPrevNight = reminders.find { it.type == TaskReminderType.REAL_PREV_NIGHT }
        assertNotNull(realPrevNight)
        assertEquals(createTime(2026, Calendar.OCTOBER, 24, 20), realPrevNight!!.triggerTimeMillis)

        // 最終1時間前 (10/25 22:59)
        val realOneHour = reminders.find { it.type == TaskReminderType.REAL_ONE_HOUR }
        assertNotNull(realOneHour)
        assertEquals(createTime(2026, Calendar.OCTOBER, 25, 22, 59), realOneHour!!.triggerTimeMillis)
    }

    @Test
    fun testRequestCodes_areUniqueAndNonNegative() {
        val taskIdA = "task-alpha"
        val taskIdB = "task-beta"

        val codesA = TaskReminderHelper.getAllRequestCodes(taskIdA)
        val codesB = TaskReminderHelper.getAllRequestCodes(taskIdB)

        assertEquals(4, codesA.size)
        assertEquals(4, codesB.size)

        // 全て正の整数
        codesA.forEach { assertTrue(it >= 0) }
        codesB.forEach { assertTrue(it >= 0) }

        // 同じタスク内で重複なし
        assertEquals(codesA.distinct().size, codesA.size)
        // 異なるタスクで重複なし
        assertTrue(codesA.intersect(codesB.toSet()).isEmpty())
    }

    @Test
    fun testPastTimingsAreExcluded() {
        // 現在: 10/24 21:00 (前日夜 20:00 は既に過ぎている)
        val now = createTime(2026, Calendar.OCTOBER, 24, 21)
        val deadline = createTime(2026, Calendar.OCTOBER, 25, 12)

        val task = TaskItem(
            id = "task-3",
            title = "急ぎのタスク",
            deadlineMillis = deadline,
            basePriority = 5
        )

        val reminders = TaskReminderHelper.calculateReminders(task, now)
        // 前日夜(10/24 20:00)は過去なので除外、1時間前(10/25 11:00)のみ残る
        assertEquals(1, reminders.size)
        assertEquals(TaskReminderType.REAL_ONE_HOUR, reminders[0].type)
    }
}
