package com.example.voicevox

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ScheduleEventHelper {

    /**
     * イベントの表示用時刻文字列を生成する。
     * - 終日の場合: "終日"
     * - 終了時刻が指定されている場合: "09:00〜10:30"
     * - 開始時刻のみの場合: "09:00"
     */
    fun formatTimeDisplay(startTime: Long, endTime: Long?, isAllDay: Boolean): String {
        if (isAllDay) return "終日"
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return if (endTime != null && endTime > startTime) {
            "${sdf.format(Date(startTime))}〜${sdf.format(Date(endTime))}"
        } else {
            sdf.format(Date(startTime))
        }
    }

    /**
     * カード上の終了時刻テキスト（例: "〜10:30"）を生成する。
     */
    fun formatEndTimeLabel(endTime: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "〜${sdf.format(Date(endTime))}"
    }

    /**
     * ソート用の分数値を算出する。
     * 終日イベントは最上位に表示するため負の値 (-1L) を返す。
     */
    fun calculateSortTime(startTime: Long, isAllDay: Boolean): Long {
        if (isAllDay) return -1L
        val cal = Calendar.getInstance().apply { timeInMillis = startTime }
        return cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
    }
}
