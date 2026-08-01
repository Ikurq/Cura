package com.example.voicevox.core

import com.example.voicevox.core.alarm.AlarmPlanner
import com.example.voicevox.core.attendance.AttendanceService
import com.example.voicevox.core.character.CharacterEngine
import com.example.voicevox.core.ics.IcsParser
import com.example.voicevox.core.repository.AlarmRepository
import com.example.voicevox.core.repository.AttendanceRepository
import com.example.voicevox.core.repository.PlayerRepository
import com.example.voicevox.core.repository.ScheduleRepository
import com.example.voicevox.core.repository.SettingsRepository
import com.example.voicevox.core.repository.TaskRepository
import com.example.voicevox.core.schedule.DeviceCalendarProvider
import com.example.voicevox.core.schedule.ScheduleService
import com.example.voicevox.core.storage.KeyValueStoreFactory

/**
 * 共通ロジックの入り口。Swift / Kotlin どちらからもこれ1つを持てばよい。
 *
 * ```swift
 * let cura = Cura(storeFactory: UserDefaultsStoreFactory(), deviceCalendar: EventKitCalendar())
 * let items = cura.schedule.itemsOn(date: today)
 * ```
 */
class Cura(
    storeFactory: KeyValueStoreFactory,
    deviceCalendar: DeviceCalendarProvider? = null,
) {
    val settings: SettingsRepository = SettingsRepository(storeFactory)
    val alarms: AlarmRepository = AlarmRepository(storeFactory)
    val tasks: TaskRepository = TaskRepository(storeFactory)
    val schedules: ScheduleRepository = ScheduleRepository(storeFactory)
    val attendanceStore: AttendanceRepository = AttendanceRepository(storeFactory)
    val player: PlayerRepository = PlayerRepository(storeFactory)

    val schedule: ScheduleService = ScheduleService(
        scheduleRepository = schedules,
        taskRepository = tasks,
        attendanceRepository = attendanceStore,
        settings = settings,
        deviceCalendar = deviceCalendar,
    )

    val attendance: AttendanceService = AttendanceService(schedules, attendanceStore)

    val character: CharacterEngine = CharacterEngine(
        playerRepository = player,
        taskRepository = tasks,
        alarmRepository = alarms,
        scheduleService = schedule,
    )

    val alarmPlanner: AlarmPlanner = AlarmPlanner(
        alarmRepository = alarms,
        taskRepository = tasks,
        scheduleService = schedule,
        settings = settings,
    )

    val icsParser: IcsParser = IcsParser()

    /**
     * 取得済みの iCal 本文をキャッシュに取り込む。
     * 取得(HTTP)はプラットフォーム側の仕事。
     */
    fun importIcs(bodies: List<String>) {
        val events = bodies.flatMap { icsParser.parse(it) }
        schedules.saveIcsEvents(events)
    }

    /**
     * 鳴り終わった単発アラームを引退させる。
     *
     * 繰り返しの無いアラームは、鳴ったあとそのままにしておくと翌日また鳴ってしまう。
     * Android 版 AlarmAlertActivity の後始末と同じ規則で、タスク読み上げ付きは
     * その日限りとみなして削除、それ以外は無効化する。
     * 繰り返しアラームには何もしない。
     */
    fun retireAlarmAfterFiring(id: String) {
        val alarm = alarms.find(id) ?: return
        if (alarm.repeatDays.isNotEmpty()) return

        if (alarm.readTasks) {
            alarms.delete(id)
        } else {
            alarms.upsert(alarm.copy(isEnabled = false))
        }
    }

    /** タスクを完了にして、経験値を加算する。 */
    fun completeTasks(ids: List<String>) {
        val all = tasks.all()
        val targets = all.filter { it.id in ids && !it.isCompleted }
        if (targets.isEmpty()) return

        tasks.save(all.map { if (it.id in ids) it.copy(isCompleted = true) else it })
        player.addExp(targets.sumOf { it.expReward })
    }
}
