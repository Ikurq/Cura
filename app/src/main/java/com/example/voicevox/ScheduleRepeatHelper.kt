package com.example.voicevox

import java.util.Calendar
import java.util.UUID

object ScheduleRepeatHelper {

    /**
     * 日本の年度末（3月31日 23:59:59）を算出する。
     * 3月以降に作成された場合は、翌年3月31日を年度末として扱う。
     * 1〜2月に作成された場合は、当年3月31日を年度末として扱う。
     */
    fun calculateFiscalYearEnd(baseDate: Calendar): Calendar {
        val eventYear = baseDate.get(Calendar.YEAR)
        val eventMonth = baseDate.get(Calendar.MONTH) // 0-based: 0=Jan, 2=Mar, 3=Apr
        val endYear = if (eventMonth >= Calendar.MARCH) eventYear + 1 else eventYear
        return (baseDate.clone() as Calendar).apply {
            set(Calendar.YEAR, endYear)
            set(Calendar.MONTH, Calendar.MARCH)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
    }

    /**
     * 基準日時から終了日時まで、毎週指定曜日に繰り返す ScheduleEvent のリストを生成する。
     */
    fun generateWeeklyOccurrences(
        summary: String,
        startCal: Calendar,
        endCal: Calendar,
        location: String = "",
        isAttendanceTracked: Boolean = false,
        repeatGroupId: String = UUID.randomUUID().toString(),
        durationMillis: Long? = null,
        isAllDay: Boolean = false
    ): List<ScheduleEvent> {
        val result = mutableListOf<ScheduleEvent>()
        val curCal = (startCal.clone() as Calendar)

        while (!curCal.after(endCal)) {
            val eventStart = curCal.timeInMillis
            val eventEnd = durationMillis?.let { eventStart + it }
            result.add(
                ScheduleEvent(
                    id = UUID.randomUUID().toString(),
                    summary = summary,
                    startTime = eventStart,
                    location = location,
                    isAttendanceTracked = isAttendanceTracked,
                    repeatGroupId = repeatGroupId,
                    endTime = eventEnd,
                    isAllDay = isAllDay
                )
            )
            curCal.add(Calendar.WEEK_OF_YEAR, 1)
        }

        // 終了日が開始日より前の設定でも最低限1件は生成
        if (result.isEmpty()) {
            val eventStart = startCal.timeInMillis
            val eventEnd = durationMillis?.let { eventStart + it }
            result.add(
                ScheduleEvent(
                    id = UUID.randomUUID().toString(),
                    summary = summary,
                    startTime = eventStart,
                    location = location,
                    isAttendanceTracked = isAttendanceTracked,
                    repeatGroupId = repeatGroupId,
                    endTime = eventEnd,
                    isAllDay = isAllDay
                )
            )
        }

        return result
    }
}
