package com.example.voicevox

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleExporterTest {

    private fun createCalendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun exportToIcs_emptyList_returnsValidEmptyCalendar() {
        val ics = ScheduleExporter.exportToIcs(emptyList())
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("VERSION:2.0"))
        assertTrue(ics.contains("PRODID:-//Cura:CareYou//Schedule//JA"))
        assertTrue(ics.contains("END:VCALENDAR"))
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun exportToIcs_timedEvent_generatesCorrectVEvent() {
        val startCal = createCalendar(2026, Calendar.OCTOBER, 30, 10, 0)
        val endCal = createCalendar(2026, Calendar.OCTOBER, 30, 11, 30)

        val event = ScheduleEvent(
            id = "test-event-1",
            summary = "定例会議",
            startTime = startCal.timeInMillis,
            endTime = endCal.timeInMillis,
            location = "第1会議室"
        )

        val ics = ScheduleExporter.exportToIcs(listOf(event))
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("UID:test-event-1@curacareyou"))
        assertTrue(ics.contains("SUMMARY:定例会議"))
        assertTrue(ics.contains("LOCATION:第1会議室"))
        assertTrue(ics.contains("DTSTART:20261030T100000"))
        assertTrue(ics.contains("DTEND:20261030T113000"))
        assertTrue(ics.contains("END:VEVENT"))
    }

    @Test
    fun exportToIcs_allDayEvent_generatesDateValuesWithNextDayEnd() {
        val startCal = createCalendar(2026, Calendar.OCTOBER, 30, 0, 0)

        val event = ScheduleEvent(
            id = "test-all-day-1",
            summary = "文化祭",
            startTime = startCal.timeInMillis,
            isAllDay = true,
            location = ""
        )

        val ics = ScheduleExporter.exportToIcs(listOf(event))
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261030"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261031"))
        assertTrue(ics.contains("SUMMARY:文化祭"))
        assertTrue(ics.contains("END:VEVENT"))
    }

    @Test
    fun exportToIcs_specialCharacters_properlyEscaped() {
        val startCal = createCalendar(2026, Calendar.OCTOBER, 30, 14, 0)

        val event = ScheduleEvent(
            id = "test-escape-1",
            summary = "研究会,発表;まとめ\\資料\nメモ",
            startTime = startCal.timeInMillis,
            location = "実験棟A, 301号室"
        )

        val ics = ScheduleExporter.exportToIcs(listOf(event))
        assertTrue(ics.contains("SUMMARY:研究会\\,発表\\;まとめ\\\\資料\\nメモ"))
        assertTrue(ics.contains("LOCATION:実験棟A\\, 301号室"))
    }

    @Test
    fun exportToIcs_noEndTime_defaultsToOneHourLater() {
        val startCal = createCalendar(2026, Calendar.OCTOBER, 30, 15, 0)

        val event = ScheduleEvent(
            id = "test-no-end",
            summary = "打ち合わせ",
            startTime = startCal.timeInMillis,
            endTime = null,
            location = ""
        )

        val ics = ScheduleExporter.exportToIcs(listOf(event))
        assertTrue(ics.contains("DTSTART:20261030T150000"))
        assertTrue(ics.contains("DTEND:20261030T160000"))
    }
}
