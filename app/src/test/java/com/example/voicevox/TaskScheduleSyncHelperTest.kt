package com.example.voicevox

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class TaskScheduleSyncHelperTest {

    private fun createCalendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun buildScheduleEvent_createsEventWithCorrectProperties() {
        val cal = createCalendar(2026, Calendar.OCTOBER, 30, 14, 0)
        val task = TaskItem(
            id = "task-1",
            title = "レポート提出",
            deadlineMillis = cal.timeInMillis,
            basePriority = 3
        )

        val event = TaskScheduleSyncHelper.buildScheduleEvent(task)

        assertEquals("レポート提出", event.summary)
        assertEquals(cal.timeInMillis, event.startTime)
        assertEquals(cal.timeInMillis + 3600000L, event.endTime)
        assertEquals("タスク", event.location)
        assertFalse(event.isAllDay)
        assertNotNull(event.id)
    }

    @Test
    fun buildScheduleEvent_preservesExistingLinkedEventId() {
        val cal = createCalendar(2026, Calendar.OCTOBER, 30, 14, 0)
        val task = TaskItem(
            id = "task-1",
            title = "買い物",
            deadlineMillis = cal.timeInMillis,
            basePriority = 2,
            linkedEventId = "event-xyz"
        )

        val event = TaskScheduleSyncHelper.buildScheduleEvent(task)

        assertEquals("event-xyz", event.id)
        assertEquals("買い物", event.summary)
    }

    @Test
    fun syncTaskToSchedule_addsNewEventAndReturnsLinkedTask() {
        val cal = createCalendar(2026, Calendar.OCTOBER, 30, 15, 0)
        val task = TaskItem(
            id = "task-new",
            title = "資料作成",
            deadlineMillis = cal.timeInMillis,
            basePriority = 4,
            linkedEventId = null
        )

        val initialEvents = listOf(
            ScheduleEvent(id = "existing-1", summary = "ミーティング", startTime = 1000L, location = "会議室")
        )

        val (updatedEvents, updatedTask) = TaskScheduleSyncHelper.syncTaskToSchedule(initialEvents, task)

        assertEquals(2, updatedEvents.size)
        assertNotNull(updatedTask.linkedEventId)
        val addedEvent = updatedEvents.find { it.id == updatedTask.linkedEventId }
        assertNotNull(addedEvent)
        assertEquals("資料作成", addedEvent?.summary)
        assertEquals(cal.timeInMillis, addedEvent?.startTime)
    }

    @Test
    fun syncTaskToSchedule_updatesExistingEventWithoutDuplication() {
        val cal = createCalendar(2026, Calendar.OCTOBER, 30, 16, 0)
        val task = TaskItem(
            id = "task-existing",
            title = "資料修正（更新版）",
            deadlineMillis = cal.timeInMillis,
            basePriority = 4,
            linkedEventId = "linked-event-1"
        )

        val initialEvents = listOf(
            ScheduleEvent(id = "linked-event-1", summary = "資料修正", startTime = 5000L, location = "タスク"),
            ScheduleEvent(id = "other-event", summary = "ランチ", startTime = 8000L, location = "食堂")
        )

        val (updatedEvents, updatedTask) = TaskScheduleSyncHelper.syncTaskToSchedule(initialEvents, task)

        assertEquals(2, updatedEvents.size)
        assertEquals("linked-event-1", updatedTask.linkedEventId)
        val updatedEvent = updatedEvents.find { it.id == "linked-event-1" }
        assertNotNull(updatedEvent)
        assertEquals("資料修正（更新版）", updatedEvent?.summary)
        assertEquals(cal.timeInMillis, updatedEvent?.startTime)
    }

    @Test
    fun removeTaskFromSchedule_removesMatchingEvent() {
        val initialEvents = listOf(
            ScheduleEvent(id = "event-to-remove", summary = "タスク予定", startTime = 1000L, location = "タスク"),
            ScheduleEvent(id = "keep-event", summary = "授業", startTime = 2000L, location = "教室")
        )

        val result = TaskScheduleSyncHelper.removeTaskFromSchedule(initialEvents, "event-to-remove")

        assertEquals(1, result.size)
        assertEquals("keep-event", result[0].id)
    }

    @Test
    fun buildScheduleEventForDate_setsSpecificDatePreservingTime() {
        val deadlineCal = createCalendar(2026, Calendar.OCTOBER, 25, 18, 30)
        val task = TaskItem(
            id = "task-date",
            title = "宿題",
            deadlineMillis = deadlineCal.timeInMillis,
            basePriority = 3
        )

        val targetDate = createCalendar(2026, Calendar.NOVEMBER, 1, 0, 0)
        val event = TaskScheduleSyncHelper.buildScheduleEventForDate(task, targetDate)

        val resultCal = Calendar.getInstance().apply { timeInMillis = event.startTime }
        assertEquals(2026, resultCal.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, resultCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, resultCal.get(Calendar.MINUTE))
        assertEquals("宿題", event.summary)
    }

    @Test
    fun updateTaskLinkedEventId_updatesTargetTaskLinkedEventId() {
        val tasks = listOf(
            TaskItem(id = "task-1", title = "タスク1", deadlineMillis = 1000L, basePriority = 1, linkedEventId = null),
            TaskItem(id = "task-2", title = "タスク2", deadlineMillis = 2000L, basePriority = 2, linkedEventId = null)
        )

        val updated = TaskScheduleSyncHelper.updateTaskLinkedEventId(tasks, "task-1", "event-abc")

        assertEquals("event-abc", updated.find { it.id == "task-1" }?.linkedEventId)
        assertNull(updated.find { it.id == "task-2" }?.linkedEventId)
    }

    @Test
    fun updateTaskLinkedEventId_clearsLinkedIdWhenNullPassed() {
        val tasks = listOf(
            TaskItem(id = "task-1", title = "タスク1", deadlineMillis = 1000L, basePriority = 1, linkedEventId = "event-abc")
        )

        val updated = TaskScheduleSyncHelper.updateTaskLinkedEventId(tasks, "task-1", null)

        assertNull(updated[0].linkedEventId)
    }
}
