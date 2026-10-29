package com.example.voicevox

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class ScheduleRepeatHelperTest {

    @Test
    fun calculateFiscalYearEnd_inOctober_returnsNextYearMarch31() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 22, 10, 0, 0)
        }
        val end = ScheduleRepeatHelper.calculateFiscalYearEnd(cal)
        assertEquals(2027, end.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, end.get(Calendar.MONTH))
        assertEquals(31, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun calculateFiscalYearEnd_inJanuary_returnsSameYearMarch31() {
        val cal = Calendar.getInstance().apply {
            set(2027, Calendar.JANUARY, 15, 10, 0, 0)
        }
        val end = ScheduleRepeatHelper.calculateFiscalYearEnd(cal)
        assertEquals(2027, end.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, end.get(Calendar.MONTH))
        assertEquals(31, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun calculateFiscalYearEnd_inMarch_returnsNextYearMarch31() {
        val cal = Calendar.getInstance().apply {
            set(2027, Calendar.MARCH, 10, 10, 0, 0)
        }
        val end = ScheduleRepeatHelper.calculateFiscalYearEnd(cal)
        assertEquals(2028, end.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, end.get(Calendar.MONTH))
        assertEquals(31, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun generateWeeklyOccurrences_createsWeeklyEventsUntilEnd() {
        val startCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 9, 0, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 23, 23, 59, 59)
        }

        val events = ScheduleRepeatHelper.generateWeeklyOccurrences(
            summary = "研究会",
            startCal = startCal,
            endCal = endCal,
            location = "実験室A",
            isAttendanceTracked = true
        )

        // 10/1 (Thu), 10/8 (Thu), 10/15 (Thu), 10/22 (Thu) -> 4 events
        assertEquals(4, events.size)
        val repeatGroupId = events[0].repeatGroupId
        assertNotNull(repeatGroupId)

        for (event in events) {
            assertEquals("研究会", event.summary)
            assertEquals("実験室A", event.location)
            assertTrue(event.isAttendanceTracked)
            assertEquals(repeatGroupId, event.repeatGroupId)
        }
    }

    @Test
    fun generateWeeklyOccurrences_withDurationAndAllDay_propagatesCorrectly() {
        val startCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 9, 0, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 15, 23, 59, 59)
        }
        val duration = 90 * 60 * 1000L // 90分

        val timedEvents = ScheduleRepeatHelper.generateWeeklyOccurrences(
            summary = "定例会議",
            startCal = startCal,
            endCal = endCal,
            durationMillis = duration,
            isAllDay = false
        )

        assertEquals(3, timedEvents.size)
        for (event in timedEvents) {
            assertFalse(event.isAllDay)
            assertNotNull(event.endTime)
            assertEquals(duration, event.endTime!! - event.startTime)
        }

        val allDayEvents = ScheduleRepeatHelper.generateWeeklyOccurrences(
            summary = "定期休校日",
            startCal = startCal,
            endCal = endCal,
            isAllDay = true
        )

        assertEquals(3, allDayEvents.size)
        for (event in allDayEvents) {
            assertTrue(event.isAllDay)
        }
    }

    @Test
    fun generateWeeklyOccurrences_endDateBeforeStart_createsAtLeastOneEvent() {
        val startCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 10, 9, 0, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 9, 0, 0)
        }

        val events = ScheduleRepeatHelper.generateWeeklyOccurrences(
            summary = "単発予定",
            startCal = startCal,
            endCal = endCal
        )

        assertEquals(1, events.size)
        assertEquals("単発予定", events[0].summary)
    }
}
