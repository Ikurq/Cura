package com.example.voicevox.core

import com.example.voicevox.core.calendar.JapaneseHolidays
import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.model.AlarmItem
import com.example.voicevox.core.model.TaskItem
import com.example.voicevox.core.repository.PlayerRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class TaskPriorityTest {

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        CuraTime.toMillis(LocalDateTime(year, month, day, hour, 0))

    private fun task(deadline: Long, base: Int, completed: Boolean = false) =
        TaskItem("t", "テスト", deadline, base, null, completed)

    @Test
    fun completedTasksDropToZero() {
        val now = at(2026, 7, 27)
        assertEquals(0, task(at(2026, 7, 20), 3, completed = true).currentPriority(now))
    }

    @Test
    fun deadlineTodayOrPastIsAlwaysFive() {
        val now = at(2026, 7, 27, hour = 23)
        // 同じ日なら、締切時刻を過ぎていても 5
        assertEquals(5, task(at(2026, 7, 27, hour = 9), 1).currentPriority(now))
        assertEquals(5, task(at(2026, 7, 1), 1).currentPriority(now))
    }

    @Test
    fun nearDeadlinesEscalate() {
        val now = at(2026, 7, 27)
        assertEquals(4, task(at(2026, 7, 28), 1).currentPriority(now))
        assertEquals(3, task(at(2026, 7, 29), 1).currentPriority(now))
    }

    @Test
    fun distantDeadlinesKeepBasePriority() {
        val now = at(2026, 7, 27)
        assertEquals(2, task(at(2026, 8, 30), 2).currentPriority(now))
    }

    @Test
    fun dayComparisonIgnoresTimeOfDay() {
        // 23:59 から翌 00:01 は「1日後」であって、2分後ではない
        val now = at(2026, 7, 27, hour = 23)
        assertEquals(4, task(at(2026, 7, 28, hour = 0), 1).currentPriority(now))
    }

    @Test
    fun lowPriorityTasksRewardMoreExp() {
        // 優先度1は基本20 + ボーナス50 で、優先度2(40)より多い
        assertEquals(70L, task(0, 1).expReward)
        assertEquals(40L, task(0, 2).expReward)
        assertEquals(100L, task(0, 5).expReward)
    }
}

class AlarmScheduleTest {

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        CuraTime.toMillis(LocalDateTime(year, month, day, hour, minute))

    private fun alarm(hour: Int, minute: Int, repeatDays: List<Int> = emptyList()) =
        AlarmItem("a", hour, minute, "起きて", 3, "ずんだもん", repeatDays = repeatDays)

    @Test
    fun oneShotAlarmFiresTodayWhenStillAhead() {
        val now = at(2026, 7, 27, 6)
        assertEquals(at(2026, 7, 27, 7), alarm(7, 0).nextTriggerMillis(now))
    }

    @Test
    fun oneShotAlarmRollsOverToTomorrow() {
        val now = at(2026, 7, 27, 8)
        assertEquals(at(2026, 7, 28, 7), alarm(7, 0).nextTriggerMillis(now))
    }

    @Test
    fun repeatingAlarmPicksTheNearestConfiguredDay() {
        // 2026-07-27 は月曜。水(4)と金(6)に設定
        val now = at(2026, 7, 27, 8)
        assertEquals(at(2026, 7, 29, 7), alarm(7, 0, listOf(4, 6)).nextTriggerMillis(now))
    }

    @Test
    fun repeatingAlarmFiresLaterToday() {
        // 月曜の朝6時。月(2)に設定してあるので、同じ日の7時
        val now = at(2026, 7, 27, 6)
        assertEquals(at(2026, 7, 27, 7), alarm(7, 0, listOf(2)).nextTriggerMillis(now))
    }

    @Test
    fun repeatingAlarmWrapsToNextWeek() {
        // 月曜の朝8時、月曜だけの設定 → 翌週の月曜
        val now = at(2026, 7, 27, 8)
        assertEquals(at(2026, 8, 3, 7), alarm(7, 0, listOf(2)).nextTriggerMillis(now))
    }
}

class JapaneseHolidayTest {

    @Test
    fun fixedHolidays() {
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2026, 1, 1)))
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2026, 5, 5)))
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2026, 11, 23)))
    }

    @Test
    fun happyMondays() {
        // 2026年の成人の日は1月12日(第2月曜)
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2026, 1, 12)))
        assertFalse(JapaneseHolidays.isHoliday(LocalDate(2026, 1, 5)))
    }

    @Test
    fun equinoxes() {
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2026, 3, 20)))
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2026, 9, 23)))
    }

    @Test
    fun substituteHolidayAfterSunday() {
        // 2026-02-23(天皇誕生日)は月曜なので振替は無い。
        // 2027-01-01 は金曜。日曜の祝日で確かめる: 2026-11-23 は月曜なのでこちらも不可。
        // 2032-02-11(建国記念の日)は水曜。日曜になる年を選ぶ: 2029-04-29 は日曜。
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2029, 4, 29)))
        assertTrue(JapaneseHolidays.isHoliday(LocalDate(2029, 4, 30)))
    }

    @Test
    fun ordinaryWeekdaysAreNotHolidays() {
        assertFalse(JapaneseHolidays.isHoliday(LocalDate(2026, 7, 27)))
        assertFalse(JapaneseHolidays.isHoliday(LocalDate(2026, 6, 10)))
    }
}

class LevelTest {

    @Test
    fun levelStartsAtOne() {
        val info = PlayerRepository.levelOf(0)
        assertEquals(1, info.level)
        assertEquals(0L, info.currentExp)
        assertEquals(100L, info.requiredExp)
    }

    @Test
    fun everyHundredExpIsOneLevel() {
        assertEquals(2, PlayerRepository.levelOf(100).level)
        assertEquals(2, PlayerRepository.levelOf(199).level)
        assertEquals(3, PlayerRepository.levelOf(200).level)
        assertEquals(31, PlayerRepository.levelOf(3050).level)
    }

    @Test
    fun currentExpIsTheRemainderWithinTheLevel() {
        val info = PlayerRepository.levelOf(250)
        assertEquals(3, info.level)
        assertEquals(50L, info.currentExp)
    }
}
