package com.example.voicevox

import java.util.*

object HolidayHelper {

    /**
     * 日本の祝日かどうかを判定する (簡易版)
     */
    fun isJapaneseHoliday(calendar: Calendar): Boolean {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1 // 0-indexed
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        // 固定祝日
        if (month == 1 && day == 1) return true   // 元日
        if (month == 2 && day == 11) return true  // 建国記念の日
        if (month == 2 && day == 23) return true  // 天皇誕生日
        if (month == 4 && day == 29) return true  // 昭和の日
        if (month == 5 && day == 3) return true   // 憲法記念日
        if (month == 5 && day == 4) return true   // みどりの日
        if (month == 5 && day == 5) return true   // こどもの日
        if (month == 8 && day == 11) return true  // 山の日
        if (month == 11 && day == 3) return true  // 文化の日
        if (month == 11 && day == 23) return true // 勤労感謝の日

        // ハッピーマンデー (第○月曜日)
        if (month == 1 && isNthDayOfWeek(calendar, 2, Calendar.MONDAY)) return true  // 成人の日
        if (month == 7 && isNthDayOfWeek(calendar, 3, Calendar.MONDAY)) return true  // 海の日
        if (month == 9 && isNthDayOfWeek(calendar, 3, Calendar.MONDAY)) return true  // 敬老の日
        if (month == 10 && isNthDayOfWeek(calendar, 2, Calendar.MONDAY)) return true // スポーツの日

        // 春分の日・秋分の日 (簡易計算式)
        if (month == 3 && day == calculateVernalEquinox(year)) return true
        if (month == 9 && day == calculateAutumnalEquinox(year)) return true

        // 振替休日 (祝日が日曜日の場合、翌月曜日が休み)
        // 厳密には連休も考慮が必要だが、簡易版として日曜チェック
        if (dayOfWeek == Calendar.MONDAY) {
            val yesterday = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            if (isJapaneseHoliday(yesterday)) return true
        }

        return false
    }

    private fun isNthDayOfWeek(calendar: Calendar, n: Int, dayOfWeek: Int): Boolean {
        return calendar.get(Calendar.DAY_OF_WEEK) == dayOfWeek &&
               (calendar.get(Calendar.DAY_OF_MONTH) - 1) / 7 == (n - 1)
    }

    private fun calculateVernalEquinox(year: Int): Int {
        return when {
            year <= 1947 -> 0
            year <= 1979 -> (20.8357 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
            year <= 2099 -> (20.8431 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
            else -> (21.8510 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
        }
    }

    private fun calculateAutumnalEquinox(year: Int): Int {
        return when {
            year <= 1947 -> 0
            year <= 1979 -> (23.2588 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
            year <= 2099 -> (23.2488 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
            else -> (24.2488 + 0.242194 * (year - 1980) - (year - 1980) / 4).toInt()
        }
    }
}
