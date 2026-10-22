package com.example.voicevox

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TaskItemTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createCalendar(year: Int, month: Int, day: Int, hour: Int = 12): Calendar {
        return Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun jsonDeserialization_backwardsCompatibility_targetDeadlineDefaultsToNull() {
        // Legacy JSON format from ver2.2 without targetDeadlineMillis
        val legacyJson = """{"id":"task-1","title":"レポート提出","deadlineMillis":1761234567890,"basePriority":3,"linkedEventId":null,"isCompleted":false}"""
        val task = json.decodeFromString<TaskItem>(legacyJson)

        assertEquals("task-1", task.id)
        assertEquals("レポート提出", task.title)
        assertEquals(1761234567890L, task.deadlineMillis)
        assertEquals(3, task.basePriority)
        assertNull(task.targetDeadlineMillis)
        assertFalse(task.isCompleted)
        assertEquals(1761234567890L, task.getEffectiveDeadline())
    }

    @Test
    fun jsonSerialization_withTargetDeadline_roundTripSucceeds() {
        val original = TaskItem(
            id = "task-2",
            title = "プレゼン資料作成",
            deadlineMillis = 1761500000000L,
            basePriority = 2,
            targetDeadlineMillis = 1761400000000L
        )

        val serialized = json.encodeToString(original)
        val decoded = json.decodeFromString<TaskItem>(serialized)

        assertEquals(original.id, decoded.id)
        assertEquals(original.title, decoded.title)
        assertEquals(original.deadlineMillis, decoded.deadlineMillis)
        assertEquals(original.targetDeadlineMillis, decoded.targetDeadlineMillis)
        assertEquals(1761400000000L, decoded.getEffectiveDeadline())
    }

    @Test
    fun getCurrentPriority_whenCompleted_returns0() {
        val refDate = createCalendar(2026, Calendar.OCTOBER, 23)
        val today = createCalendar(2026, Calendar.OCTOBER, 23).timeInMillis

        val task = TaskItem(
            id = "task-3",
            title = "完了タスク",
            deadlineMillis = today,
            basePriority = 5,
            targetDeadlineMillis = today,
            isCompleted = true
        )

        assertEquals(0, task.getCurrentPriority(refDate))
    }

    @Test
    fun getCurrentPriority_withTargetDeadline_increasesUrgency() {
        val refDate = createCalendar(2026, Calendar.OCTOBER, 23) // Friday

        val realDeadlineNextWeek = createCalendar(2026, Calendar.OCTOBER, 30).timeInMillis // 7 days later
        val targetToday = createCalendar(2026, Calendar.OCTOBER, 23, 18).timeInMillis // Today
        val targetTomorrow = createCalendar(2026, Calendar.OCTOBER, 24, 18).timeInMillis // Tomorrow
        val targetDayAfter = createCalendar(2026, Calendar.OCTOBER, 25, 18).timeInMillis // Day after tomorrow (2 days)
        val targetOverdue = createCalendar(2026, Calendar.OCTOBER, 22, 18).timeInMillis // Yesterday

        // 1. Target deadline is today -> P5 even if real deadline is next week and basePriority is 1
        val taskTargetToday = TaskItem("1", "Task", realDeadlineNextWeek, 1, targetDeadlineMillis = targetToday)
        assertEquals(5, taskTargetToday.getCurrentPriority(refDate))

        // 2. Target deadline is tomorrow -> P4
        val taskTargetTomorrow = TaskItem("2", "Task", realDeadlineNextWeek, 1, targetDeadlineMillis = targetTomorrow)
        assertEquals(4, taskTargetTomorrow.getCurrentPriority(refDate))

        // 3. Target deadline is 2 days away -> P3
        val taskTargetDayAfter = TaskItem("3", "Task", realDeadlineNextWeek, 1, targetDeadlineMillis = targetDayAfter)
        assertEquals(3, taskTargetDayAfter.getCurrentPriority(refDate))

        // 4. Target deadline is yesterday (missed target) -> P5
        val taskTargetOverdue = TaskItem("4", "Task", realDeadlineNextWeek, 1, targetDeadlineMillis = targetOverdue)
        assertEquals(5, taskTargetOverdue.getCurrentPriority(refDate))
    }

    @Test
    fun getCurrentPriority_withoutTargetDeadline_usesRealDeadline() {
        val refDate = createCalendar(2026, Calendar.OCTOBER, 23)

        val realToday = createCalendar(2026, Calendar.OCTOBER, 23).timeInMillis
        val realTomorrow = createCalendar(2026, Calendar.OCTOBER, 24).timeInMillis
        val realFar = createCalendar(2026, Calendar.NOVEMBER, 10).timeInMillis

        val task1 = TaskItem("1", "T1", realToday, 1)
        assertEquals(5, task1.getCurrentPriority(refDate))

        val task2 = TaskItem("2", "T2", realTomorrow, 1)
        assertEquals(4, task2.getCurrentPriority(refDate))

        val task3 = TaskItem("3", "T3", realFar, 2)
        assertEquals(2, task3.getCurrentPriority(refDate))
    }

    @Test
    fun getEffectiveDeadline_prefersTargetWhenPresent() {
        val taskWithTarget = TaskItem("1", "T", 2000L, 1, targetDeadlineMillis = 1000L)
        assertEquals(1000L, taskWithTarget.getEffectiveDeadline())

        val taskWithoutTarget = TaskItem("2", "T", 2000L, 1, targetDeadlineMillis = null)
        assertEquals(2000L, taskWithoutTarget.getEffectiveDeadline())
    }
}