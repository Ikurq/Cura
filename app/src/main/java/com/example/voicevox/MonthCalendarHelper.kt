package com.example.voicevox

import java.util.Calendar

data class MonthDay(
    val year: Int,
    val month: Int, // 0-based: 0 = January, 11 = December
    val dayOfMonth: Int,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val dayOfWeek: Int, // Calendar.SUNDAY(1) .. Calendar.SATURDAY(7)
    val startOfDayMillis: Long
)

object MonthCalendarHelper {

    const val TOTAL_GRID_CELLS = 42 // 6 rows * 7 columns

    /**
     * Generates 42 days for the month view grid starting on Sunday.
     */
    fun generateMonthDays(year: Int, month: Int, today: Calendar = Calendar.getInstance()): List<MonthDay> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysBefore = firstDayOfWeek - Calendar.SUNDAY

        val gridStartCal = (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, -daysBefore)
        }

        val todayYear = today.get(Calendar.YEAR)
        val todayMonth = today.get(Calendar.MONTH)
        val todayDay = today.get(Calendar.DAY_OF_MONTH)

        val days = ArrayList<MonthDay>(TOTAL_GRID_CELLS)
        val cursor = gridStartCal.clone() as Calendar

        for (i in 0 until TOTAL_GRID_CELLS) {
            val cellYear = cursor.get(Calendar.YEAR)
            val cellMonth = cursor.get(Calendar.MONTH)
            val cellDay = cursor.get(Calendar.DAY_OF_MONTH)
            val isCurrentMonth = (cellYear == year && cellMonth == month)
            val isToday = (cellYear == todayYear && cellMonth == todayMonth && cellDay == todayDay)
            val dayOfWeek = cursor.get(Calendar.DAY_OF_WEEK)

            days.add(
                MonthDay(
                    year = cellYear,
                    month = cellMonth,
                    dayOfMonth = cellDay,
                    isCurrentMonth = isCurrentMonth,
                    isToday = isToday,
                    dayOfWeek = dayOfWeek,
                    startOfDayMillis = cursor.timeInMillis
                )
            )
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }

        return days
    }

    /**
     * Formats Year/Month title, e.g. "2026年 10月"
     */
    fun formatYearMonthTitle(year: Int, month: Int): String {
        return "${year}年 ${month + 1}月"
    }

    /**
     * Formats Month-only title, e.g. "10月"
     */
    fun formatMonthOnlyTitle(month: Int): String {
        return "${month + 1}月"
    }

    /**
     * Computes the Year and Month (0-based) for a given ViewPager2 position based on centerPosition and baseCal.
     */
    fun getYearMonthForPosition(position: Int, centerPosition: Int, baseCal: Calendar = Calendar.getInstance()): Pair<Int, Int> {
        val offset = position - centerPosition
        val cal = (baseCal.clone() as Calendar).apply {
            add(Calendar.MONTH, offset)
        }
        return Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    /**
     * Computes the ViewPager2 position for a target Year and Month (0-based) based on centerPosition and baseCal.
     */
    fun getPositionForYearMonth(year: Int, month: Int, centerPosition: Int, baseCal: Calendar = Calendar.getInstance()): Int {
        val baseYear = baseCal.get(Calendar.YEAR)
        val baseMonth = baseCal.get(Calendar.MONTH)
        val offset = (year - baseYear) * 12 + (month - baseMonth)
        return centerPosition + offset
    }
}
