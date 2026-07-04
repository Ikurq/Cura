package com.example.voicevox

import java.util.Calendar

data class TaskItem(
    val id: String,
    val title: String,
    val deadlineMillis: Long,
    val basePriority: Int, // 1-5
    val linkedEventId: String? = null, // Linked ScheduleEvent ID
    var isCompleted: Boolean = false // 完了フラグ
) {
    fun getCurrentPriority(): Int {
        // 完了済みタスクは優先度を最低にする
        if (isCompleted) return 0

        val now = Calendar.getInstance()
        val deadline = Calendar.getInstance().apply { timeInMillis = deadlineMillis }
        
        // 日付のみで比較するために正規化
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val targetDay = Calendar.getInstance().apply {
            timeInMillis = deadlineMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val diffMillis = targetDay.timeInMillis - today.timeInMillis
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        
        return when {
            diffDays <= 0 -> 5      // 当日、または過ぎている
            diffDays == 1 -> 4      // 明日
            diffDays == 2 -> 3      // 明後日
            else -> basePriority    // それ以外は設定した基本優先度
        }.coerceIn(1, 5)
    }
}
