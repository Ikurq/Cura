package com.example.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CoreLogicBugFixTest {

    private fun cal(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Calendar {
        return Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun scheduleLoader_filterTasksForToday_excludesCompletedTasks() {
        val today = cal(2026, Calendar.NOVEMBER, 23, 9, 0)
        val todayNoon = cal(2026, Calendar.NOVEMBER, 23, 12, 0).timeInMillis
        val tomorrowNoon = cal(2026, Calendar.NOVEMBER, 24, 12, 0).timeInMillis

        val tasks = listOf(
            TaskItem(id = "1", title = "未完了の今日タスク", deadlineMillis = todayNoon, basePriority = 3, isCompleted = false),
            TaskItem(id = "2", title = "完了済みの今日タスク", deadlineMillis = todayNoon, basePriority = 5, isCompleted = true),
            TaskItem(id = "3", title = "目標期限が今日の未完了タスク", deadlineMillis = tomorrowNoon, targetDeadlineMillis = todayNoon, basePriority = 2, isCompleted = false),
            TaskItem(id = "4", title = "目標期限が今日の完了タスク", deadlineMillis = tomorrowNoon, targetDeadlineMillis = todayNoon, basePriority = 2, isCompleted = true),
            TaskItem(id = "5", title = "明日の未完了タスク", deadlineMillis = tomorrowNoon, basePriority = 4, isCompleted = false)
        )

        val result = ScheduleLoader.filterActiveTasksForDate(tasks, today)
        assertEquals(listOf("未完了の今日タスク", "目標期限が今日の未完了タスク"), result)
    }

    @Test
    fun attendanceStatus_supportsEarlyStatusAndMapsCorrectly() {
        assertEquals(AttendanceStatus.ATTEND, AttendanceStatus.fromString("ATTEND"))
        assertEquals(AttendanceStatus.ABSENT, AttendanceStatus.fromString("ABSENT"))
        assertEquals(AttendanceStatus.LATE, AttendanceStatus.fromString("LATE"))
        assertEquals(AttendanceStatus.EARLY, AttendanceStatus.fromString("EARLY"))
        assertEquals(AttendanceStatus.NONE, AttendanceStatus.fromString("NONE"))
        assertEquals(AttendanceStatus.NONE, AttendanceStatus.fromString("INVALID"))
    }

    @Test
    fun holidayHelper_identifiesFixedHappyMondayEquinoxAndSubstituteHolidays() {
        // 元日 (2026-01-01)
        assertTrue(HolidayHelper.isJapaneseHoliday(cal(2026, Calendar.JANUARY, 1)))
        // 勤労感謝の日 (2026-11-23 Monday)
        assertTrue(HolidayHelper.isJapaneseHoliday(cal(2026, Calendar.NOVEMBER, 23)))
        // 成人の日 (2026-01-12 2nd Monday)
        assertTrue(HolidayHelper.isJapaneseHoliday(cal(2026, Calendar.JANUARY, 12)))
        // 春分の日 (2026-03-20)
        assertTrue(HolidayHelper.isJapaneseHoliday(cal(2026, Calendar.MARCH, 20)))
        // 振替休日: 2026-05-03 (Sunday, 憲法記念日), 05-04 (Monday, みどりの日), 05-05 (Tuesday, こどもの日), 05-06 (Wednesday, 振替休日)
        // 日曜が祝日(2027-02-11 is Thu, 2026-11-03 is Tue, 2024-02-11 was Sun -> 2024-02-12 Mon is substitute)
        assertTrue(HolidayHelper.isJapaneseHoliday(cal(2024, Calendar.FEBRUARY, 12)))
        // 平日 (2026-11-24 Tuesday)
        assertFalse(HolidayHelper.isJapaneseHoliday(cal(2026, Calendar.NOVEMBER, 24)))
    }

    @Test
    fun findNextActiveAlarm_respectsRepeatDaysAndEnabledState() {
        val tokyoTz = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        val now = Calendar.getInstance(tokyoTz).apply {
            set(2026, Calendar.NOVEMBER, 23, 8, 0, 0) // Monday 08:00
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val satAlarm = AlarmItem(
            id = "sat", hour = 8, minute = 30, message = "土曜", speakerId = 3, speakerName = "ずんだもん",
            isEnabled = true, readTasks = false, repeatDays = listOf(Calendar.SATURDAY)
        )
        val tueAlarm = AlarmItem(
            id = "tue", hour = 7, minute = 0, message = "火曜", speakerId = 3, speakerName = "ずんだもん",
            isEnabled = true, readTasks = false, repeatDays = listOf(Calendar.TUESDAY)
        )
        val disabledToday = AlarmItem(
            id = "dis", hour = 8, minute = 10, message = "無効", speakerId = 3, speakerName = "ずんだもん",
            isEnabled = false, readTasks = false, repeatDays = listOf(Calendar.MONDAY)
        )

        val next = AlarmScheduler.findNextActiveAlarm(listOf(satAlarm, tueAlarm, disabledToday), now, tokyoTz)
        assertEquals("tue", next?.id)
    }
}
