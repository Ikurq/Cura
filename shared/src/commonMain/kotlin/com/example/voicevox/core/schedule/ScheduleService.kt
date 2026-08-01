package com.example.voicevox.core.schedule

import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.model.AttendanceStatus
import com.example.voicevox.core.model.IcsEvent
import com.example.voicevox.core.model.ScheduleItem
import com.example.voicevox.core.repository.AttendanceRepository
import com.example.voicevox.core.repository.ScheduleRepository
import com.example.voicevox.core.repository.SettingsRepository
import com.example.voicevox.core.repository.TaskRepository
import kotlinx.datetime.LocalDate

/**
 * 端末カレンダーの読み出し。EventKit / CalendarContract の差を吸収する。
 * 権限が無い・同期が無効なら空リストを返す実装であること。
 */
interface DeviceCalendarProvider {
    fun events(startMillis: Long, endMillis: Long, calendarIds: List<String>): List<IcsEvent>
    fun calendars(): List<com.example.voicevox.core.model.DeviceCalendarInfo>
    fun hasAccess(): Boolean
}

/**
 * 「その日の予定」を1つに束ねる。
 *
 * Cura は予定を3系統から集める: 手動で足したもの、購読中の iCal のキャッシュ、
 * 端末カレンダー。Android 版の ScheduleLoader と同じ順序・同じ絞り込みで統合する。
 */
class ScheduleService(
    private val scheduleRepository: ScheduleRepository,
    private val taskRepository: TaskRepository,
    private val attendanceRepository: AttendanceRepository,
    private val settings: SettingsRepository,
    private val deviceCalendar: DeviceCalendarProvider?,
) {

    /** 指定日の予定を時刻順で返す。 */
    fun eventsOn(date: LocalDate): List<IcsEvent> {
        val result = mutableListOf<IcsEvent>()

        scheduleRepository.customEvents()
            .filter { CuraTime.isOnDate(it.startTime, date) }
            .forEach {
                result.add(
                    IcsEvent(
                        summary = it.genre,
                        startTime = it.startTime,
                        endTime = it.startTime,
                        location = it.location,
                        isAttendanceTracked = it.isAttendanceTracked,
                        attendanceStatus = it.attendanceStatus,
                    )
                )
            }

        scheduleRepository.icsEvents()
            .filter { CuraTime.isOnDate(it.startTime, date) }
            .forEach { result.add(it) }

        if (settings.syncDeviceCalendar && deviceCalendar != null) {
            result += deviceCalendar.events(
                CuraTime.startOfDayMillis(date),
                CuraTime.endOfDayMillis(date),
                settings.selectedCalendarIds,
            )
        }

        return result.sortedBy { it.startTime }
    }

    fun eventsToday(): List<IcsEvent> = eventsOn(CuraTime.today())

    /** 今から見て次に来る予定。 */
    fun nextEvent(nowMillis: Long = CuraTime.nowMillis()): IcsEvent? =
        eventsToday().filter { it.startTime > nowMillis }.minByOrNull { it.startTime }

    /**
     * ホーム・時間割画面に出す行。予定とタスクを時刻順に混ぜる。
     * 出欠を追跡している科目には、記録済みの状態を載せる。
     */
    fun itemsOn(date: LocalDate): List<ScheduleItem> {
        val dayKey = CuraTime.dayKey(date)

        val eventItems = eventsOn(date).map { event ->
            val tracked = event.isAttendanceTracked || attendanceRepository.isTracked(event.summary)
            val status = if (tracked) {
                // 手動予定は自身が状態を持ち、iCal 由来は AttendancePrefs 側に持つ
                val own = AttendanceStatus.parse(event.attendanceStatus)
                if (own != AttendanceStatus.NONE) own else attendanceRepository.status(event.summary, dayKey)
            } else {
                AttendanceStatus.NONE
            }
            ScheduleItem(
                id = "${event.summary}@${event.startTime}",
                timeLabel = CuraTime.formatHourMinute(event.startTime),
                title = event.summary,
                subtitle = event.location.ifBlank { "予定" },
                sortTime = event.startTime,
                attendanceStatus = status,
                isAttendanceTracked = tracked,
            )
        }

        val taskItems = taskRepository.pending()
            .filter { CuraTime.isOnDate(it.deadlineMillis, date) }
            .map { task ->
                ScheduleItem(
                    id = task.id,
                    timeLabel = CuraTime.formatHourMinute(task.deadlineMillis),
                    title = task.title,
                    subtitle = "📝 タスク",
                    sortTime = task.deadlineMillis,
                )
            }

        return (eventItems + taskItems).sortedBy { it.sortTime }
    }

    /** アラームの読み上げに載せる、その日のタスク名。 */
    fun taskTitlesFor(millis: Long): List<String> = taskRepository.titlesDueOn(millis)
}
