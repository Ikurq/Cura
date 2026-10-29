package com.example.voicevox

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ScheduleEventHelperTest {

    private fun createTime(hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun formatTimeDisplay_whenAllDay_returnsAllDayString() {
        val start = createTime(9, 0)
        val end = createTime(18, 0)
        val text = ScheduleEventHelper.formatTimeDisplay(start, end, isAllDay = true)
        assertEquals("終日", text)
    }

    @Test
    fun formatTimeDisplay_withValidEndTime_returnsRange() {
        val start = createTime(9, 0)
        val end = createTime(10, 30)
        val text = ScheduleEventHelper.formatTimeDisplay(start, end, isAllDay = false)
        assertEquals("09:00〜10:30", text)
    }

    @Test
    fun formatTimeDisplay_withoutEndTime_returnsStartTimeOnly() {
        val start = createTime(14, 15)
        val text = ScheduleEventHelper.formatTimeDisplay(start, null, isAllDay = false)
        assertEquals("14:15", text)
    }

    @Test
    fun formatTimeDisplay_withEndTimeBeforeStart_returnsStartTimeOnly() {
        val start = createTime(15, 0)
        val end = createTime(14, 0) // Invalid
        val text = ScheduleEventHelper.formatTimeDisplay(start, end, isAllDay = false)
        assertEquals("15:00", text)
    }

    @Test
    fun formatEndTimeLabel_returnsTildePrefix() {
        val end = createTime(11, 45)
        val text = ScheduleEventHelper.formatEndTimeLabel(end)
        assertEquals("〜11:45", text)
    }

    @Test
    fun calculateSortTime_whenAllDay_returnsNegative() {
        val start = createTime(10, 0)
        val sortTime = ScheduleEventHelper.calculateSortTime(start, isAllDay = true)
        assertEquals(-1L, sortTime)
    }

    @Test
    fun calculateSortTime_timedEvent_returnsMinutesFromMidnight() {
        val start = createTime(9, 30)
        val sortTime = ScheduleEventHelper.calculateSortTime(start, isAllDay = false)
        assertEquals(9 * 60 + 30L, sortTime)
    }
}
