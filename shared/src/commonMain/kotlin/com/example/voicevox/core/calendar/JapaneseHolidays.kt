package com.example.voicevox.core.calendar

import com.example.voicevox.core.core.CuraTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * 日本の祝日判定(簡易版)。Android 版の HolidayHelper の移植。
 *
 * 固定祝日・ハッピーマンデー・春分秋分の近似式・振替休日(前日が祝日の月曜)まで。
 * 国民の休日(前後を祝日に挟まれた平日)や、法改正による単年の移動は見ていない。
 */
object JapaneseHolidays {

    fun isHoliday(date: LocalDate): Boolean {
        val month = date.monthNumber
        val day = date.dayOfMonth

        // 固定祝日
        if (month == 1 && day == 1) return true    // 元日
        if (month == 2 && day == 11) return true   // 建国記念の日
        if (month == 2 && day == 23) return true   // 天皇誕生日
        if (month == 4 && day == 29) return true   // 昭和の日
        if (month == 5 && day == 3) return true    // 憲法記念日
        if (month == 5 && day == 4) return true    // みどりの日
        if (month == 5 && day == 5) return true    // こどもの日
        if (month == 8 && day == 11) return true   // 山の日
        if (month == 11 && day == 3) return true   // 文化の日
        if (month == 11 && day == 23) return true  // 勤労感謝の日

        // ハッピーマンデー
        if (month == 1 && isNthDayOfWeek(date, 2, DayOfWeek.MONDAY)) return true   // 成人の日
        if (month == 7 && isNthDayOfWeek(date, 3, DayOfWeek.MONDAY)) return true   // 海の日
        if (month == 9 && isNthDayOfWeek(date, 3, DayOfWeek.MONDAY)) return true   // 敬老の日
        if (month == 10 && isNthDayOfWeek(date, 2, DayOfWeek.MONDAY)) return true  // スポーツの日

        // 春分・秋分(近似式)
        if (month == 3 && day == vernalEquinox(date.year)) return true
        if (month == 9 && day == autumnalEquinox(date.year)) return true

        // 振替休日: 日曜が祝日なら翌月曜。祝日が連続する場合は考慮していない。
        if (date.dayOfWeek == DayOfWeek.MONDAY && isHoliday(date.minus(1, DateTimeUnit.DAY))) return true

        return false
    }

    fun isHoliday(millis: Long): Boolean = isHoliday(CuraTime.toLocalDate(millis))

    private fun isNthDayOfWeek(date: LocalDate, n: Int, dayOfWeek: DayOfWeek): Boolean =
        date.dayOfWeek == dayOfWeek && (date.dayOfMonth - 1) / 7 == (n - 1)

    private fun vernalEquinox(year: Int): Int = when {
        year <= 1947 -> 0
        year <= 1979 -> (20.8357 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
        year <= 2099 -> (20.8431 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
        else -> (21.8510 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
    }

    private fun autumnalEquinox(year: Int): Int = when {
        year <= 1947 -> 0
        year <= 1979 -> (23.2588 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
        year <= 2099 -> (23.2488 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
        else -> (24.2488 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
    }
}
