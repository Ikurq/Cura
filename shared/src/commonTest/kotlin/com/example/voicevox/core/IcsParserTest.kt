package com.example.voicevox.core

import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.ics.IcsParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class IcsParserTest {

    private val parser = IcsParser()

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
    }

    @Test
    fun parsesUtcTimestamps() {
        val events = parser.parse(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:情報理論
            LOCATION:M7
            DTSTART:20231027T100000Z
            DTEND:20231027T113000Z
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )

        assertEquals(1, events.size)
        assertEquals("情報理論", events[0].summary)
        assertEquals("M7", events[0].location)
        assertEquals(Instant.parse("2023-10-27T10:00:00Z").toEpochMilliseconds(), events[0].startTime)
        assertEquals(Instant.parse("2023-10-27T11:30:00Z").toEpochMilliseconds(), events[0].endTime)
    }

    @Test
    fun parsesFloatingLocalTimestampsInDeviceTimeZone() {
        val events = parser.parse(
            """
            BEGIN:VEVENT
            SUMMARY:ローカル
            DTSTART;TZID=Asia/Tokyo:20231027T100000
            DTEND;TZID=Asia/Tokyo:20231027T113000
            END:VEVENT
            """.trimIndent()
        )

        // JST の 10:00 は UTC 01:00
        assertEquals(Instant.parse("2023-10-27T01:00:00Z").toEpochMilliseconds(), events[0].startTime)
    }

    @Test
    fun parsesAllDayDates() {
        val events = parser.parse(
            """
            BEGIN:VEVENT
            SUMMARY:終日
            DTSTART;VALUE=DATE:20231027
            END:VEVENT
            """.trimIndent()
        )
        assertEquals(Instant.parse("2023-10-26T15:00:00Z").toEpochMilliseconds(), events[0].startTime)
    }

    @Test
    fun unfoldsContinuationLines() {
        val events = parser.parse(
            listOf(
                "BEGIN:VEVENT",
                "SUMMARY:とても長い講義名がここで折り返され",
                " ています",
                "DTSTART:20231027T100000Z",
                "END:VEVENT",
            )
        )
        assertEquals("とても長い講義名がここで折り返されています", events[0].summary)
    }

    @Test
    fun unescapesSpecialCharacters() {
        val events = parser.parse(
            listOf(
                "BEGIN:VEVENT",
                """SUMMARY:A\,B\;C\nD""",
                "END:VEVENT",
            )
        )
        assertEquals("A,B;C\nD", events[0].summary)
    }

    @Test
    fun ignoresMalformedDatesInsteadOfThrowing() {
        val events = parser.parse(
            listOf("BEGIN:VEVENT", "SUMMARY:壊れた", "DTSTART:NOT-A-DATE", "END:VEVENT")
        )
        assertEquals(1, events.size)
        assertEquals(0L, events[0].startTime)
    }

    @Test
    fun readsMultipleEvents() {
        val events = parser.parse(
            """
            BEGIN:VEVENT
            SUMMARY:1限
            DTSTART:20231027T000000Z
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:2限
            DTSTART:20231027T020000Z
            END:VEVENT
            """.trimIndent()
        )
        assertEquals(listOf("1限", "2限"), events.map { it.summary })
        assertTrue(events[0].startTime < events[1].startTime)
    }
}
