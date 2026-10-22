package com.example.voicevox

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class MonthCalendarHelperTest {

    @Test
    fun generateMonthDays_alwaysReturns42Days() {
        val days = MonthCalendarHelper.generateMonthDays(2026, Calendar.OCTOBER)
        assertEquals(42, days.size)
    }

    @Test
    fun generateMonthDays_october2026_startsOnSundaySep27() {
        val today = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 23, 12, 0, 0)
        }
        val days = MonthCalendarHelper.generateMonthDays(2026, Calendar.OCTOBER, today)

        // First item should be Sep 27, 2026 (Sunday)
        val firstDay = days[0]
        assertEquals(2026, firstDay.year)
        assertEquals(Calendar.SEPTEMBER, firstDay.month)
        assertEquals(27, firstDay.dayOfMonth)
        assertEquals(Calendar.SUNDAY, firstDay.dayOfWeek)
        assertFalse(firstDay.isCurrentMonth)
        assertFalse(firstDay.isToday)

        // Oct 1 should be Thursday (index 4)
        val oct1 = days[4]
        assertEquals(2026, oct1.year)
        assertEquals(Calendar.OCTOBER, oct1.month)
        assertEquals(1, oct1.dayOfMonth)
        assertEquals(Calendar.THURSDAY, oct1.dayOfWeek)
        assertTrue(oct1.isCurrentMonth)
        assertFalse(oct1.isToday)

        // Oct 23 should be Friday (index 4 + 22 = 26) and isToday == true
        val oct23 = days[26]
        assertEquals(2026, oct23.year)
        assertEquals(Calendar.OCTOBER, oct23.month)
        assertEquals(23, oct23.dayOfMonth)
        assertEquals(Calendar.FRIDAY, oct23.dayOfWeek)
        assertTrue(oct23.isCurrentMonth)
        assertTrue(oct23.isToday)

        // Oct 31 should be Saturday (index 34)
        val oct31 = days[34]
        assertEquals(2026, oct31.year)
        assertEquals(Calendar.OCTOBER, oct31.month)
        assertEquals(31, oct31.dayOfMonth)
        assertEquals(Calendar.SATURDAY, oct31.dayOfWeek)
        assertTrue(oct31.isCurrentMonth)
        assertFalse(oct31.isToday)

        // Nov 1 should be Sunday (index 35) and isCurrentMonth == false
        val nov1 = days[35]
        assertEquals(2026, nov1.year)
        assertEquals(Calendar.NOVEMBER, nov1.month)
        assertEquals(1, nov1.dayOfMonth)
        assertEquals(Calendar.SUNDAY, nov1.dayOfWeek)
        assertFalse(nov1.isCurrentMonth)

        // Last item (index 41) should be Nov 7, 2026 (Saturday)
        val lastDay = days[41]
        assertEquals(2026, lastDay.year)
        assertEquals(Calendar.NOVEMBER, lastDay.month)
        assertEquals(7, lastDay.dayOfMonth)
        assertEquals(Calendar.SATURDAY, lastDay.dayOfWeek)
        assertFalse(lastDay.isCurrentMonth)
    }

    @Test
    fun formatTitles_returnsCorrectFormattedStrings() {
        assertEquals("2026年 10月", MonthCalendarHelper.formatYearMonthTitle(2026, Calendar.OCTOBER))
        assertEquals("10月", MonthCalendarHelper.formatMonthOnlyTitle(Calendar.OCTOBER))
        assertEquals("2027年 1月", MonthCalendarHelper.formatYearMonthTitle(2027, Calendar.JANUARY))
        assertEquals("1月", MonthCalendarHelper.formatMonthOnlyTitle(Calendar.JANUARY))
    }

    @Test
    fun positionCalculations_areReversible() {
        val center = 1000
        val baseCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 0, 0, 0)
        }

        // Center position corresponds to Oct 2026
        val (year0, month0) = MonthCalendarHelper.getYearMonthForPosition(center, center, baseCal)
        assertEquals(2026, year0)
        assertEquals(Calendar.OCTOBER, month0)

        // Next month (Nov 2026) -> center + 1
        val (year1, month1) = MonthCalendarHelper.getYearMonthForPosition(center + 1, center, baseCal)
        assertEquals(2026, year1)
        assertEquals(Calendar.NOVEMBER, month1)

        // Next year (Oct 2027) -> center + 12
        val (year12, month12) = MonthCalendarHelper.getYearMonthForPosition(center + 12, center, baseCal)
        assertEquals(2027, year12)
        assertEquals(Calendar.OCTOBER, month12)

        // Reverse check
        assertEquals(center, MonthCalendarHelper.getPositionForYearMonth(2026, Calendar.OCTOBER, center, baseCal))
        assertEquals(center + 1, MonthCalendarHelper.getPositionForYearMonth(2026, Calendar.NOVEMBER, center, baseCal))
        assertEquals(center + 12, MonthCalendarHelper.getPositionForYearMonth(2027, Calendar.OCTOBER, center, baseCal))
        assertEquals(center - 1, MonthCalendarHelper.getPositionForYearMonth(2026, Calendar.SEPTEMBER, center, baseCal))
    }
}
