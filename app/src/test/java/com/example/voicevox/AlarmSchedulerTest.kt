package com.example.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlarmSchedulerTest {

    private val tokyoTz = TimeZone.getTimeZone("Asia/Tokyo")

    private fun calMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
        millis: Int = 0
    ): Long {
        return Calendar.getInstance(tokyoTz).apply {
            set(year, month, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis
    }

    private fun sampleAlarm(
        id: String = "alarm-1",
        repeatDays: List<Int> = emptyList(),
        isOneShot: Boolean = false,
        isEnabled: Boolean = true
    ): AlarmItem {
        return AlarmItem(
            id = id,
            hour = 7,
            minute = 0,
            message = "起床時間です",
            speakerId = 3,
            speakerName = "ずんだもん",
            isEnabled = isEnabled,
            readTasks = false,
            vibrate = true,
            repeatDays = repeatDays,
            isOneShot = isOneShot,
            requireQrCode = false
        )
    }

    @Test
    fun calculateNextTriggerMillis_oneShotFutureToday_schedulesToday() {
        val now = calMillis(2026, Calendar.NOVEMBER, 22, 6, 30)
        val expected = calMillis(2026, Calendar.NOVEMBER, 22, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = emptyList(),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_subMinuteBeforeTarget_schedulesTodayWithZeroSeconds() {
        val now = calMillis(2026, Calendar.NOVEMBER, 22, 6, 59, second = 45, millis = 500)
        val expected = calMillis(2026, Calendar.NOVEMBER, 22, 7, 0, second = 0, millis = 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = emptyList(),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_oneShotPastToday_schedulesTomorrow() {
        val now = calMillis(2026, Calendar.NOVEMBER, 22, 8, 0)
        val expected = calMillis(2026, Calendar.NOVEMBER, 23, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = emptyList(),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_exactSameMinute_schedulesNextDay() {
        val now = calMillis(2026, Calendar.NOVEMBER, 22, 7, 0, 0)
        val expected = calMillis(2026, Calendar.NOVEMBER, 23, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = emptyList(),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_repeatDays_picksNearestMatchingDayInWeek() {
        val now = calMillis(2026, Calendar.NOVEMBER, 22, 8, 0)
        val expected = calMillis(2026, Calendar.NOVEMBER, 23, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = listOf(Calendar.FRIDAY, Calendar.MONDAY, Calendar.WEDNESDAY),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_repeatDaysTodayLater_schedulesToday() {
        val now = calMillis(2026, Calendar.NOVEMBER, 23, 6, 15)
        val expected = calMillis(2026, Calendar.NOVEMBER, 23, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = listOf(Calendar.MONDAY, Calendar.WEDNESDAY),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_repeatDaysSameDayPast_schedulesSevenDaysLater() {
        // 2026-11-23 is Monday 07:05, repeatDays=[Mon, Mon] (重複含む) -> 2026-11-30 07:00
        val now = calMillis(2026, Calendar.NOVEMBER, 23, 7, 5)
        val expected = calMillis(2026, Calendar.NOVEMBER, 30, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = listOf(Calendar.MONDAY, Calendar.MONDAY),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_repeatDaysWeekWrap_schedulesNextWeek() {
        val now = calMillis(2026, Calendar.NOVEMBER, 28, 9, 0)
        val expected = calMillis(2026, Calendar.NOVEMBER, 30, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = listOf(Calendar.MONDAY),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun calculateNextTriggerMillis_invalidRepeatDays_fallsBackSafely() {
        val now = calMillis(2026, Calendar.NOVEMBER, 22, 8, 0)
        val expected = calMillis(2026, Calendar.NOVEMBER, 23, 7, 0)

        val actual = AlarmScheduler.calculateNextTriggerMillis(
            hour = 7,
            minute = 0,
            repeatDays = listOf(0, 99),
            nowMillis = now,
            timeZone = tokyoTz
        )

        assertEquals(expected, actual)
    }

    @Test
    fun resolveAudioFilePath_prefersPrimaryAndSupportsLegacyKey() {
        assertEquals(
            "/data/user/0/alarm.wav",
            AlarmScheduler.resolveAudioFilePath("/data/user/0/alarm.wav", "/legacy.wav")
        )
        assertEquals(
            "/legacy.wav",
            AlarmScheduler.resolveAudioFilePath(null, "/legacy.wav")
        )
        assertEquals(
            "/legacy.wav",
            AlarmScheduler.resolveAudioFilePath("", "/legacy.wav")
        )
        assertNull(
            AlarmScheduler.resolveAudioFilePath(null, null)
        )
    }

    @Test
    fun applyAlarmDismissedState_oneShot_removesItemFromList() {
        val oneShot = sampleAlarm(id = "one-shot", isOneShot = true)
        val other = sampleAlarm(id = "keep")

        val result = AlarmScheduler.applyAlarmDismissedState(listOf(oneShot, other), "one-shot")

        assertTrue(result.didModifyList)
        assertNull(result.itemToReschedule)
        assertEquals(1, result.updatedList.size)
        assertEquals("keep", result.updatedList[0].id)
    }

    @Test
    fun applyAlarmDismissedState_repeating_keepsEnabledAndReturnsForReschedule() {
        val repeating = sampleAlarm(id = "rep", repeatDays = listOf(Calendar.MONDAY, Calendar.FRIDAY))

        val result = AlarmScheduler.applyAlarmDismissedState(listOf(repeating), "rep")

        assertFalse(result.didModifyList)
        assertNotNull(result.itemToReschedule)
        assertEquals("rep", result.itemToReschedule?.id)
        assertTrue(result.updatedList[0].isEnabled)
    }

    @Test
    fun applyAlarmDismissedState_singleNonOneShot_disablesAlarm() {
        val single = sampleAlarm(id = "single", repeatDays = emptyList(), isOneShot = false, isEnabled = true)

        val result = AlarmScheduler.applyAlarmDismissedState(listOf(single), "single")

        assertTrue(result.didModifyList)
        assertNull(result.itemToReschedule)
        assertFalse(result.updatedList[0].isEnabled)
    }

    @Test
    fun applyAlarmDismissedState_unknownId_leavesListUnchanged() {
        val existing = sampleAlarm(id = "existing")

        val result = AlarmScheduler.applyAlarmDismissedState(listOf(existing), "missing")

        assertFalse(result.didModifyList)
        assertNull(result.itemToReschedule)
        assertEquals(1, result.updatedList.size)
    }
}
