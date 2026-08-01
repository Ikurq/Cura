package com.example.voicevox.core.core

import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Cura のデータは全て「エポックミリ秒 + 端末のタイムゾーン」で保存されている。
 * 移植にあたって解釈を変えないよう、変換はここに集約する。
 */
object CuraTime {

    /** 端末のタイムゾーン。テストから差し替えられるようにしてある。 */
    var timeZone: TimeZone = TimeZone.currentSystemDefault()

    /** 現在時刻。テストから差し替えられるようにしてある。 */
    var clock: Clock = Clock.System

    fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    fun toLocalDateTime(millis: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)

    fun toLocalDate(millis: Long): LocalDate = toLocalDateTime(millis).date

    fun toMillis(dateTime: LocalDateTime): Long =
        dateTime.toInstant(timeZone).toEpochMilliseconds()

    fun today(): LocalDate = toLocalDate(nowMillis())

    /** 日付を日数ぶん進める(負なら戻す)。 */
    fun datePlusDays(date: LocalDate, days: Int): LocalDate = date.plus(days, DateTimeUnit.DAY)

    fun tomorrow(): LocalDate = datePlusDays(today(), 1)

    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDayIn(timeZone).toEpochMilliseconds()

    fun endOfDayMillis(date: LocalDate): Long =
        date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone).toEpochMilliseconds() - 1

    fun isSameDay(millisA: Long, millisB: Long): Boolean =
        toLocalDate(millisA) == toLocalDate(millisB)

    fun isOnDate(millis: Long, date: LocalDate): Boolean = toLocalDate(millis) == date

    /** 出欠記録のキーに使う日付表現(例: "2026-07-27")。Android 版と同じ形式。 */
    fun dayKey(millis: Long): String = dayKey(toLocalDate(millis))

    fun dayKey(date: LocalDate): String =
        "${pad4(date.year)}-${pad2(date.monthNumber)}-${pad2(date.dayOfMonth)}"

    /** "HH:mm"。 */
    fun formatHourMinute(millis: Long): String {
        val dt = toLocalDateTime(millis)
        return "${pad2(dt.hour)}:${pad2(dt.minute)}"
    }

    fun formatHourMinute(hour: Int, minute: Int): String = "${pad2(hour)}:${pad2(minute)}"

    /** 読み上げ用の「H時M分」。 */
    fun speakHourMinute(hour: Int, minute: Int): String = "${hour}時${minute}分"

    /**
     * 指定の時刻に対応する、今から見て次の到来時刻。
     *
     * @param repeatDays 繰り返す曜日。Android 版と同じ 1=日 … 7=土 の並び。
     *   空なら「今日のその時刻、既に過ぎていれば明日」。
     */
    fun nextOccurrence(hour: Int, minute: Int, repeatDays: List<Int>, fromMillis: Long = nowMillis()): Long {
        val time = LocalTime(hour, minute)
        val fromDate = toLocalDate(fromMillis)

        if (repeatDays.isEmpty()) {
            val todayAt = toMillis(LocalDateTime(fromDate, time))
            return if (todayAt > fromMillis) todayAt else toMillis(LocalDateTime(fromDate.plus(1, DateTimeUnit.DAY), time))
        }

        // 今日から7日先までを順に見て、曜日が一致する最初の未来の時刻を返す。
        // 週跨ぎも自然に処理できる。
        var best: Long? = null
        for (offset in 0..7) {
            val date = fromDate.plus(offset, DateTimeUnit.DAY)
            if (calendarDayOfWeek(date) !in repeatDays) continue
            val candidate = toMillis(LocalDateTime(date, time))
            if (candidate > fromMillis && (best == null || candidate < best!!)) best = candidate
        }
        return best ?: toMillis(LocalDateTime(fromDate.plus(7, DateTimeUnit.DAY), time))
    }

    /** kotlinx-datetime の DayOfWeek を Android の Calendar 表現(1=日 … 7=土)に直す。 */
    fun calendarDayOfWeek(date: LocalDate): Int = when (date.dayOfWeek) {
        DayOfWeek.SUNDAY -> 1
        DayOfWeek.MONDAY -> 2
        DayOfWeek.TUESDAY -> 3
        DayOfWeek.WEDNESDAY -> 4
        DayOfWeek.THURSDAY -> 5
        DayOfWeek.FRIDAY -> 6
        DayOfWeek.SATURDAY -> 7
        else -> 1
    }

    /** 日付単位の差(b - a)。時刻は切り捨てる。 */
    fun dayDifference(fromMillis: Long, toMillis: Long): Int {
        val from = toLocalDate(fromMillis)
        val to = toLocalDate(toMillis)
        return (to.toEpochDays() - from.toEpochDays())
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    private fun pad4(v: Int): String = when {
        v >= 1000 -> v.toString()
        v >= 100 -> "0$v"
        v >= 10 -> "00$v"
        else -> "000$v"
    }
}

/** 1日 = 86,400,000ms。可読性のために置いている。 */
val ONE_DAY_MILLIS: Long = 1.days.inWholeMilliseconds
