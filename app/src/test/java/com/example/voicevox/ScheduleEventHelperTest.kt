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

    @Test
    fun decodeEventsSafely_withSummaryKey_parsesCorrectly() {
        val json = """[{"id":"evt-1","summary":"ミーティング","startTime":1700000000000,"location":"会議室A"}]"""
        val events = ScheduleEventHelper.decodeEventsSafely(json)
        assertEquals(1, events.size)
        assertEquals("evt-1", events[0].id)
        assertEquals("ミーティング", events[0].summary)
        assertEquals("会議室A", events[0].location)
    }

    @Test
    fun decodeEventsSafely_withLegacyGenreKey_parsesCorrectly() {
        // 過去バージョンや TaskFragment で出力されていた "genre" キーのJSON
        val json = """[{"id":"legacy-1","genre":"旧仕様の予定","startTime":1700000000000,"location":"オフィス"}]"""
        val events = ScheduleEventHelper.decodeEventsSafely(json)
        assertEquals(1, events.size)
        assertEquals("legacy-1", events[0].id)
        assertEquals("旧仕様の予定", events[0].summary)
        assertEquals("オフィス", events[0].location)
    }

    @Test
    fun decodeEventsSafely_withCompletedTaskFromTaskFragment_parsesCorrectly() {
        // TaskFragment.recordCompletedTasksToSchedule が生成していたJSON
        val json = """[{"id":"completed_task_12345","genre":"[完了] 部屋の片付け","startTime":1700000000000,"location":"タスク完了","isAttendanceTracked":false,"attendanceStatus":"NONE"}]"""
        val events = ScheduleEventHelper.decodeEventsSafely(json)
        assertEquals(1, events.size)
        assertEquals("completed_task_12345", events[0].id)
        assertEquals("[完了] 部屋の片付け", events[0].summary)
        assertEquals("タスク完了", events[0].location)
    }

    @Test
    fun decodeEventsSafely_withMissingFields_usesDefaultsWithoutException() {
        // summary や location が欠落していても例外を投げずにパースできること
        val json = """[{"id":"partial-1"}]"""
        val events = ScheduleEventHelper.decodeEventsSafely(json)
        assertEquals(1, events.size)
        assertEquals("partial-1", events[0].id)
        assertEquals("", events[0].summary)
        assertEquals("", events[0].location)
        assertEquals(0L, events[0].startTime)
    }

    @Test
    fun decodeEventsSafely_withCorruptedElements_recoversValidElements() {
        // 1件壊れた要素（型不整合など）が混ざっていても、正常な要素を救出できること
        val json = """[
            {"id":"valid-1","summary":"正常な予定","startTime":1000,"location":"場所"},
            {"id": 99999, "summary": false},
            {"id":"valid-2","genre":"正常な旧形式","startTime":2000,"location":"場所2"}
        ]"""
        val events = ScheduleEventHelper.decodeEventsSafely(json)
        assertTrue(events.isNotEmpty())
        assertEquals("valid-1", events[0].id)
        assertEquals("正常な予定", events[0].summary)
    }

    @Test
    fun decodeEventsSafely_withNullOrEmpty_returnsEmptyList() {
        assertTrue(ScheduleEventHelper.decodeEventsSafely(null).isEmpty())
        assertTrue(ScheduleEventHelper.decodeEventsSafely("").isEmpty())
        assertTrue(ScheduleEventHelper.decodeEventsSafely("[]").isEmpty())
        assertTrue(ScheduleEventHelper.decodeEventsSafely("   ").isEmpty())
    }

    @Test
    fun migrateLegacyJson_replacesGenreWithSummary() {
        val legacy = """[{"id":"1","genre":"テスト"}]"""
        val migrated = ScheduleEventHelper.migrateLegacyJson(legacy)
        assertEquals("""[{"id":"1","summary":"テスト"}]""", migrated)
    }
}
