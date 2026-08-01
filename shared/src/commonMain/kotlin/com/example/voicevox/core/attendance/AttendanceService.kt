package com.example.voicevox.core.attendance

import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.model.AttendanceStatus
import com.example.voicevox.core.model.SubjectStats
import com.example.voicevox.core.repository.AttendanceRepository
import com.example.voicevox.core.repository.ScheduleRepository

/**
 * 出欠の集計。
 *
 * 同じ科目が1日に複数コマある場合、1日1件に畳む。畳むときの強さは
 * ABSENT > LATE > ATTEND で、Android 版の registerOccurrence と同じ。
 * 「午前は出たが午後を切った」なら、その日は欠席として数える。
 */
class AttendanceService(
    private val scheduleRepository: ScheduleRepository,
    private val attendanceRepository: AttendanceRepository,
) {

    fun summary(): List<SubjectStats> {
        val occurrenceDays = mutableMapOf<String, MutableSet<String>>()
        val statusByDay = mutableMapOf<String, MutableMap<String, AttendanceStatus>>()

        fun register(name: String, millis: Long, status: AttendanceStatus) {
            val dayKey = CuraTime.dayKey(millis)
            occurrenceDays.getOrPut(name) { mutableSetOf() }.add(dayKey)

            val days = statusByDay.getOrPut(name) { mutableMapOf() }
            val current = days[dayKey] ?: AttendanceStatus.NONE
            when {
                status == AttendanceStatus.ABSENT -> days[dayKey] = AttendanceStatus.ABSENT
                status == AttendanceStatus.LATE && current != AttendanceStatus.ABSENT ->
                    days[dayKey] = AttendanceStatus.LATE
                status == AttendanceStatus.ATTEND && current == AttendanceStatus.NONE ->
                    days[dayKey] = AttendanceStatus.ATTEND
            }
        }

        // 手動で追加した予定は、自分自身が出欠状態を持っている
        scheduleRepository.customEvents()
            .filter { it.isAttendanceTracked }
            .forEach { register(it.genre, it.startTime, it.status) }

        // iCal 由来は、追跡フラグと日付ごとの状態を AttendancePrefs 側に持つ
        scheduleRepository.icsEvents()
            .filter { attendanceRepository.isTracked(it.summary) }
            .forEach {
                register(it.summary, it.startTime, attendanceRepository.status(it.summary, CuraTime.dayKey(it.startTime)))
            }

        return occurrenceDays.keys.map { name ->
            val days = statusByDay[name].orEmpty()
            val absentDates = days.filterValues { it == AttendanceStatus.ABSENT }.keys.sortedDescending()

            // 手動カウンターの方が多ければそちらを採用する(記録漏れの補正用)
            val absent = maxOf(absentDates.size, attendanceRepository.manualAbsent(name))

            SubjectStats(
                name = name,
                totalScheduled = occurrenceDays[name]?.size ?: 0,
                attended = days.count { it.value == AttendanceStatus.ATTEND },
                absent = absent,
                late = days.count { it.value == AttendanceStatus.LATE },
                absentDates = absentDates,
            )
        }.sortedBy { it.name }
    }

    /** 予定1コマの出欠を記録する。iCal 由来か手動かで保存先が変わる。 */
    fun record(subject: String, startMillis: Long, status: AttendanceStatus) {
        val custom = scheduleRepository.customEvents()
            .firstOrNull { it.genre == subject && it.startTime == startMillis }

        if (custom != null) {
            scheduleRepository.upsertCustomEvent(
                custom.copy(isAttendanceTracked = true, attendanceStatus = status.name)
            )
        } else {
            attendanceRepository.setTracked(subject, true)
            attendanceRepository.setStatus(subject, CuraTime.dayKey(startMillis), status)
        }
    }

    fun adjustManualAbsent(subject: String, delta: Int) {
        val current = summary().firstOrNull { it.name == subject }?.absent
            ?: attendanceRepository.manualAbsent(subject)
        attendanceRepository.setManualAbsent(subject, current + delta)
    }
}
