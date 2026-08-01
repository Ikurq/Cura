package com.example.voicevox.core

import com.example.voicevox.core.alarm.PlannedNotification
import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.model.AlarmItem
import com.example.voicevox.core.model.AttendanceStatus
import com.example.voicevox.core.model.IcsEvent
import com.example.voicevox.core.model.ScheduleEvent
import com.example.voicevox.core.model.TaskItem
import com.example.voicevox.core.storage.StoreNames
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
    CuraTime.toMillis(LocalDateTime(year, month, day, hour, minute))

class AttendanceServiceTest {

    private lateinit var cura: Cura

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
        cura = Cura(InMemoryStoreFactory())
    }

    @Test
    fun countsOneOccurrencePerDayEvenWithMultiplePeriods() {
        // 同じ科目が同じ日に2コマ。1日として数える
        cura.schedules.saveCustomEvents(
            listOf(
                ScheduleEvent("1", "情報理論", at(2026, 7, 27, 9), isAttendanceTracked = true, attendanceStatus = "ATTEND"),
                ScheduleEvent("2", "情報理論", at(2026, 7, 27, 13), isAttendanceTracked = true, attendanceStatus = "ATTEND"),
            )
        )

        val stats = cura.attendance.summary().single()
        assertEquals(1, stats.totalScheduled)
        assertEquals(1, stats.attended)
    }

    @Test
    fun absenceWinsOverAttendanceWithinTheSameDay() {
        // 午前は出席、午後は欠席 → その日は欠席
        cura.schedules.saveCustomEvents(
            listOf(
                ScheduleEvent("1", "情報理論", at(2026, 7, 27, 9), isAttendanceTracked = true, attendanceStatus = "ATTEND"),
                ScheduleEvent("2", "情報理論", at(2026, 7, 27, 13), isAttendanceTracked = true, attendanceStatus = "ABSENT"),
            )
        )

        val stats = cura.attendance.summary().single()
        assertEquals(0, stats.attended)
        assertEquals(1, stats.absent)
        assertEquals(listOf("2026-07-27"), stats.absentDates)
    }

    @Test
    fun latenessOutranksAttendanceButNotAbsence() {
        cura.schedules.saveCustomEvents(
            listOf(
                ScheduleEvent("1", "数学", at(2026, 7, 27, 9), isAttendanceTracked = true, attendanceStatus = "ATTEND"),
                ScheduleEvent("2", "数学", at(2026, 7, 27, 13), isAttendanceTracked = true, attendanceStatus = "LATE"),
            )
        )
        assertEquals(1, cura.attendance.summary().single().late)
    }

    @Test
    fun manualCounterOverridesLowerRecordedAbsences() {
        cura.schedules.saveCustomEvents(
            listOf(ScheduleEvent("1", "英語", at(2026, 7, 27, 9), isAttendanceTracked = true, attendanceStatus = "ABSENT"))
        )
        cura.attendanceStore.setManualAbsent("英語", 4)

        assertEquals(4, cura.attendance.summary().single().absent)
    }

    @Test
    fun externalEventsUseTheAttendanceStore() {
        cura.schedules.saveIcsEvents(
            listOf(IcsEvent("線形代数", at(2026, 7, 27, 9), at(2026, 7, 27, 10)))
        )
        cura.attendanceStore.setTracked("線形代数", true)
        cura.attendanceStore.setStatus("線形代数", "2026-07-27", AttendanceStatus.ABSENT)

        val stats = cura.attendance.summary().single()
        assertEquals("線形代数", stats.name)
        assertEquals(1, stats.absent)
    }

    @Test
    fun untrackedSubjectsAreNotCounted() {
        cura.schedules.saveIcsEvents(
            listOf(IcsEvent("体育", at(2026, 7, 27, 9), at(2026, 7, 27, 10)))
        )
        assertTrue(cura.attendance.summary().isEmpty())
    }
}

class ScheduleServiceTest {

    private lateinit var cura: Cura

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
        cura = Cura(InMemoryStoreFactory())
    }

    @Test
    fun mergesCustomAndExternalEventsInTimeOrder() {
        cura.schedules.saveCustomEvents(
            listOf(ScheduleEvent("1", "バイト", at(2026, 7, 27, 18), "駅前"))
        )
        cura.schedules.saveIcsEvents(
            listOf(IcsEvent("情報理論", at(2026, 7, 27, 9), at(2026, 7, 27, 10), "M7"))
        )

        val events = cura.schedule.eventsOn(LocalDate(2026, 7, 27))
        assertEquals(listOf("情報理論", "バイト"), events.map { it.summary })
    }

    @Test
    fun filtersToTheRequestedDay() {
        cura.schedules.saveIcsEvents(
            listOf(
                IcsEvent("今日", at(2026, 7, 27, 9), at(2026, 7, 27, 10)),
                IcsEvent("明日", at(2026, 7, 28, 9), at(2026, 7, 28, 10)),
            )
        )
        assertEquals(listOf("今日"), cura.schedule.eventsOn(LocalDate(2026, 7, 27)).map { it.summary })
    }

    @Test
    fun scheduleItemsIncludePendingTasksDueThatDay() {
        cura.schedules.saveIcsEvents(
            listOf(IcsEvent("講義", at(2026, 7, 27, 9), at(2026, 7, 27, 10)))
        )
        cura.tasks.save(
            listOf(
                TaskItem("t1", "レポート", at(2026, 7, 27, 23), 3),
                TaskItem("t2", "提出済み", at(2026, 7, 27, 20), 3, isCompleted = true),
            )
        )

        val items = cura.schedule.itemsOn(LocalDate(2026, 7, 27))
        assertEquals(listOf("講義", "レポート"), items.map { it.title })
        assertEquals("📝 タスク", items[1].subtitle)
    }
}

class AlarmPlannerTest {

    private lateinit var cura: Cura

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
        cura = Cura(InMemoryStoreFactory())
    }

    private fun alarm(repeatDays: List<Int> = emptyList(), enabled: Boolean = true) =
        AlarmItem("a1", 7, 0, "起きてください", 3, "ずんだもん", isEnabled = enabled, repeatDays = repeatDays)

    @Test
    fun disabledAlarmsAreNotPlanned() {
        assertNull(cura.alarmPlanner.planAlarm(alarm(enabled = false), null, at(2026, 7, 27, 6)))
    }

    @Test
    fun vacationModeSilencesEverything() {
        cura.settings.vacationMode = true
        assertNull(cura.alarmPlanner.planAlarm(alarm(listOf(2, 3, 4, 5, 6)), null, at(2026, 7, 27, 6)))
    }

    @Test
    fun skipHolidaysAdvancesToTheNextWorkingOccurrence() {
        cura.settings.skipHolidays = true
        // 2026-11-23(月)は勤労感謝の日。月〜金の繰り返しなら翌24日(火)に送る
        val planned = cura.alarmPlanner.planAlarm(
            alarm(listOf(2, 3, 4, 5, 6)),
            null,
            at(2026, 11, 23, 6),
        )
        assertNotNull(planned)
        assertEquals(at(2026, 11, 24, 7), planned.triggerMillis)
    }

    @Test
    fun repeatingAlarmDelegatesToTheOsWhenHolidaysAreNotSkipped() {
        // 繰り返しアラームを1件だけ予約すると、初回が鳴ったあとアプリを開くまで
        // 二度と鳴らない。祝日を見なくてよいなら OS の週次繰り返しに委ねる。
        val plans = cura.alarmPlanner.planAlarms(alarm(listOf(2, 4, 6)), null, at(2026, 7, 27, 6))

        assertEquals(3, plans.size)
        assertEquals(listOf(2, 4, 6), plans.mapNotNull { it.repeatWeekday }.sorted())
        // ID が曜日ごとに分かれていないと、予約が互いを上書きしてしまう
        assertEquals(3, plans.map { it.id }.toSet().size)
    }

    @Test
    fun repeatingAlarmQueuesSeveralOccurrencesWhenHolidaysAreSkipped() {
        // 祝日判定はアプリ側にしかないので OS の繰り返しは使えない。
        // 先の回数ぶんを個別に予約する。
        cura.settings.skipHolidays = true
        val plans = cura.alarmPlanner.planAlarms(
            alarm(listOf(2, 3, 4, 5, 6)),
            null,
            at(2026, 11, 20, 6),
            occurrences = 4,
        )

        assertEquals(4, plans.size)
        assertTrue(plans.all { it.repeatWeekday == null })
        assertEquals(4, plans.map { it.id }.toSet().size)
        // 予約は時系列で単調に増える
        assertEquals(plans.map { it.triggerMillis }.sorted(), plans.map { it.triggerMillis })
        // 11/23(月)は勤労感謝の日なので入らない
        assertTrue(plans.none { CuraTime.isSameDay(it.triggerMillis, at(2026, 11, 23, 7)) })
    }

    @Test
    fun planAlarmPicksTheSoonestRepeatDay() {
        // 2026-07-28 は火曜。月・金の繰り返しなら、次に来るのは今週の金(7/31)。
        // 曜日番号順で先頭を返すと月曜(8/3)を選んでしまう。
        val planned = cura.alarmPlanner.planAlarm(alarm(listOf(2, 6)), null, at(2026, 7, 28, 6))

        assertNotNull(planned)
        assertEquals(at(2026, 7, 31, 7), planned.triggerMillis)
    }

    @Test
    fun everyAlarmPlanCarriesItsSourceAlarmId() {
        // plan.id は繰り返しぶんで接尾辞が付くので、通知から元のアラームを
        // 引き当てるには sourceId が要る
        val target = alarm(listOf(2, 4, 6))
        val plans = cura.alarmPlanner.planAlarms(target, null, at(2026, 7, 27, 6))

        assertTrue(plans.isNotEmpty())
        assertTrue(plans.all { it.sourceId == target.id })
        assertTrue(plans.any { it.id != target.id })
    }

    @Test
    fun vacationModeSilencesRepeatingAlarmsToo() {
        cura.settings.vacationMode = true
        assertTrue(cura.alarmPlanner.planAlarms(alarm(listOf(2, 3, 4, 5, 6)), null, at(2026, 7, 27, 6)).isEmpty())
    }

    @Test
    fun oneShotAlarmOnAHolidayIsDropped() {
        cura.settings.skipHolidays = true
        assertNull(cura.alarmPlanner.planAlarm(alarm(), null, at(2026, 11, 23, 6)))
    }

    @Test
    fun taskRemindersFireAnHourBeforeTheDeadline() {
        cura.tasks.save(listOf(TaskItem("t1", "レポート", at(2026, 7, 27, 18), 3)))

        val reminders = cura.alarmPlanner.planReminders(at(2026, 7, 27, 9))
        val task = reminders.single { it.kind == PlannedNotification.Kind.TASK_DEADLINE }
        assertEquals(at(2026, 7, 27, 17), task.triggerMillis)
        assertEquals("レポート", task.body)
    }

    @Test
    fun overdueTasksAreRemindedAnHourFromNow() {
        cura.tasks.save(listOf(TaskItem("t1", "遅れてる", at(2026, 7, 27, 8), 3)))

        val overdue = cura.alarmPlanner.planReminders(at(2026, 7, 27, 9))
            .single { it.kind == PlannedNotification.Kind.TASK_OVERDUE }
        assertEquals(at(2026, 7, 27, 10), overdue.triggerMillis)
    }

    @Test
    fun completedTasksProduceNoReminders() {
        cura.tasks.save(listOf(TaskItem("t1", "済", at(2026, 7, 27, 18), 3, isCompleted = true)))
        assertTrue(cura.alarmPlanner.planReminders(at(2026, 7, 27, 9)).isEmpty())
    }

    @Test
    fun eventRemindersFireTenMinutesBefore() {
        cura.schedules.saveIcsEvents(
            listOf(IcsEvent("講義", at(2026, 7, 27, 9), at(2026, 7, 27, 10)))
        )
        // 「今日」を固定するため、当日の朝を基準にする
        CuraTime.clock = FixedClock(at(2026, 7, 27, 8))

        val event = cura.alarmPlanner.planReminders(at(2026, 7, 27, 8))
            .single { it.kind == PlannedNotification.Kind.EVENT_REMINDER }
        assertEquals(at(2026, 7, 27, 8, 50), event.triggerMillis)

        CuraTime.clock = kotlinx.datetime.Clock.System
    }

    @Test
    fun notificationsCanBeTurnedOff() {
        cura.settings.taskNotification = false
        cura.settings.eventNotification = false
        cura.tasks.save(listOf(TaskItem("t1", "レポート", at(2026, 7, 27, 18), 3)))

        assertTrue(cura.alarmPlanner.planReminders(at(2026, 7, 27, 9)).isEmpty())
    }

    @Test
    fun speechTextAppendsTodaysTasksWhenRequested() {
        cura.tasks.save(listOf(TaskItem("t1", "レポート提出", at(2026, 7, 27, 23), 3)))
        val item = alarm().copy(readTasks = true)

        val text = cura.alarmPlanner.speechText(item, at(2026, 7, 27, 7))
        assertEquals("7時0分を過ぎました。起きてください。本日のタスクは、レポート提出、です。", text)
    }

    @Test
    fun speechTextOmitsTasksWhenThereAreNone() {
        val item = alarm().copy(readTasks = true)
        assertEquals("7時0分を過ぎました。起きてください", cura.alarmPlanner.speechText(item, at(2026, 7, 27, 7)))
    }

    @Test
    fun mandatoryReminderOnlyFiresWhenNoMandatoryAlarmIsSet() {
        val now = at(2026, 7, 27, 0)
        assertNotNull(cura.alarmPlanner.planMandatoryReminder(now))

        cura.alarms.save(
            listOf(
                AlarmItem(
                    "m1", 6, 30,
                    "6時30分を過ぎています。本日の予定である情報理論まであと30分を切っています。起きてください。",
                    3, "ずんだもん",
                )
            )
        )
        assertNull(cura.alarmPlanner.planMandatoryReminder(now))
    }
}

class ReminderHorizonTest {

    private lateinit var cura: Cura

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
        cura = Cura(InMemoryStoreFactory())
    }

    @Test
    fun remindersCoverFutureDaysNotJustToday() {
        // iOS はアプリを開かないと予約を張り直せないので、当日ぶんだけだと
        // 翌日以降の締切通知が一度も登録されない
        val now = at(2026, 7, 27, 9)
        cura.tasks.save(
            listOf(
                TaskItem("t1", "今日のレポート", at(2026, 7, 27, 23), 3),
                TaskItem("t2", "3日後のレポート", at(2026, 7, 30, 23), 3),
                TaskItem("t3", "来月のレポート", at(2026, 8, 27, 23), 3),
            )
        )

        val bodies = cura.alarmPlanner.planReminders(now, lookaheadDays = 7).map { it.body }

        assertTrue(bodies.contains("今日のレポート"))
        assertTrue(bodies.contains("3日後のレポート"))
        // 予約枠は有限なので、遠すぎるものは入れない
        assertTrue(!bodies.contains("来月のレポート"))
    }

    @Test
    fun mandatoryReminderIsQueuedForSeveralDays() {
        cura.settings.mandatoryReminder = true
        val plans = cura.alarmPlanner.planMandatoryReminders(at(2026, 7, 27, 9), lookaheadDays = 3)

        assertEquals(3, plans.size)
        // 同じIDだと後のものが前のものを上書きしてしまう
        assertEquals(3, plans.map { it.id }.toSet().size)
        assertEquals(plans.map { it.triggerMillis }.sorted(), plans.map { it.triggerMillis })
    }
}

class AlarmRetirementTest {

    private lateinit var cura: Cura

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
        cura = Cura(InMemoryStoreFactory())
    }

    private fun alarm(id: String, repeatDays: List<Int> = emptyList(), readTasks: Boolean = false) =
        AlarmItem(id, 7, 0, "起きてください", 3, "ずんだもん", readTasks = readTasks, repeatDays = repeatDays)

    @Test
    fun oneShotAlarmIsDisabledAfterItFires() {
        // 放っておくと翌日また鳴ってしまう
        cura.alarms.save(listOf(alarm("a1")))
        cura.retireAlarmAfterFiring("a1")

        assertEquals(false, cura.alarms.find("a1")?.isEnabled)
    }

    @Test
    fun oneShotAlarmWithTaskReadingIsDeleted() {
        // タスク読み上げ付きはその日限りの内容なので残さない
        cura.alarms.save(listOf(alarm("a1", readTasks = true)))
        cura.retireAlarmAfterFiring("a1")

        assertNull(cura.alarms.find("a1"))
    }

    @Test
    fun repeatingAlarmSurvivesFiring() {
        cura.alarms.save(listOf(alarm("a1", repeatDays = listOf(2, 3, 4, 5, 6))))
        cura.retireAlarmAfterFiring("a1")

        assertEquals(true, cura.alarms.find("a1")?.isEnabled)
    }
}

class SettingsRepositoryTest {

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
    }

    @Test
    fun readsCalendarIdsWrittenByTheAndroidSettingsScreen() {
        // Android は CalendarContract の数値IDを `[12,34]` で書く。
        // 空リストは「全カレンダー」を意味するので、ここで落とすと
        // 既存ユーザーの選択が無かったことになってしまう。
        val factory = InMemoryStoreFactory()
        factory.store(StoreNames.APP).putString("selected_calendar_ids", "[12,34]")

        assertEquals(listOf("12", "34"), Cura(factory).settings.selectedCalendarIds)
    }

    @Test
    fun readsCalendarIdsWrittenAsStrings() {
        // iOS は EventKit の文字列IDを書く
        val factory = InMemoryStoreFactory()
        factory.store(StoreNames.APP).putString("selected_calendar_ids", """["A-1","B-2"]""")

        assertEquals(listOf("A-1", "B-2"), Cura(factory).settings.selectedCalendarIds)
    }

    @Test
    fun unparseableCalendarIdsFallBackToAllCalendars() {
        val factory = InMemoryStoreFactory()
        factory.store(StoreNames.APP).putString("selected_calendar_ids", "not json")

        assertEquals(emptyList(), Cura(factory).settings.selectedCalendarIds)
    }
}

class TaskCompletionTest {

    @BeforeTest
    fun setUp() {
        CuraTime.timeZone = TimeZone.of("Asia/Tokyo")
    }

    @Test
    fun completingTasksAwardsExpToBothPlayerAndCharacter() {
        val cura = Cura(InMemoryStoreFactory())
        cura.tasks.save(
            listOf(
                TaskItem("t1", "低優先度", at(2026, 8, 30), 1),
                TaskItem("t2", "高優先度", at(2026, 8, 30), 5),
            )
        )

        cura.completeTasks(listOf("t1", "t2"))

        // 70 (優先度1 + ボーナス) + 100 (優先度5)
        assertEquals(170L, cura.player.playerExp)
        assertEquals(170L, cura.player.characterExp)
        assertTrue(cura.tasks.all().all { it.isCompleted })
    }

    @Test
    fun completingTwiceDoesNotDoubleTheReward() {
        val cura = Cura(InMemoryStoreFactory())
        cura.tasks.save(listOf(TaskItem("t1", "一度きり", at(2026, 8, 30), 3)))

        cura.completeTasks(listOf("t1"))
        cura.completeTasks(listOf("t1"))

        assertEquals(60L, cura.player.playerExp)
    }
}

private class FixedClock(private val millis: Long) : kotlinx.datetime.Clock {
    override fun now() = kotlinx.datetime.Instant.fromEpochMilliseconds(millis)
}
