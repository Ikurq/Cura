package com.example.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class IcsParserTest {

    @Test
    fun parse_unfoldsLinesAndDecodesEscapedCharacters() {
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "BEGIN:VEVENT",
            "SUMMARY:研究ミーティ",
            " ング\\,発表\\;まとめ\\\\資料\\n第1回",
            "LOCATION:第1実験室\\, A棟",
            "DTSTART:20261123T093000",
            "DTEND:20261123T110000",
            "END:VEVENT",
            "END:VCALENDAR"
        )

        val events = IcsParser().parse(lines)
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("研究ミーティング,発表;まとめ\\資料\n第1回", event.summary)
        assertEquals("第1実験室, A棟", event.location)
        assertFalse(event.isAllDay)
        assertTrue(event.endTime > event.startTime)
    }

    @Test
    fun parse_allDayEvent_setsIsAllDayTrue() {
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "SUMMARY:創立記念日",
            "DTSTART;VALUE=DATE:20261123",
            "DTEND;VALUE=DATE:20261124",
            "END:VEVENT",
            "END:VCALENDAR"
        )

        val events = IcsParser().parse(lines)
        assertEquals(1, events.size)
        assertTrue(events[0].isAllDay)
        assertEquals("創立記念日", events[0].summary)
    }

    @Test
    fun parse_utcTimestamp_parsesCorrectlyInUtc() {
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "SUMMARY:国際会議",
            "DTSTART:20261123T000000Z",
            "DTEND:20261123T010000Z",
            "END:VEVENT",
            "END:VCALENDAR"
        )

        val events = IcsParser().parse(lines)
        assertEquals(1, events.size)
        val expectedStart = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 23, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedStart, events[0].startTime)
        assertEquals(expectedStart + 3600_000L, events[0].endTime)
    }
}
