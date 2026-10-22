package com.example.voicevox

import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
data class TaskItem(
    val id: String,
    val title: String,
    val deadlineMillis: Long, // 本当のデッドライン (最終期限)
    val basePriority: Int, // 1-5
    val linkedEventId: String? = null, // Linked ScheduleEvent ID
    var isCompleted: Boolean = false, // 完了フラグ
    val targetDeadlineMillis: Long? = null // 目標デッドライン (目標期限)
) {
    fun getEffectiveDeadline(): Long {
        return targetDeadlineMillis ?: deadlineMillis
    }

    fun getCurrentPriority(referenceDate: Calendar = Calendar.getInstance()): Int {
        // 完了済みタスクは優先度を最低にする
        if (isCompleted) return 0

        // 比較基準日を正規化
        val baseDay = (referenceDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        fun calculatePriorityFor(millis: Long): Int {
            val dueDay = Calendar.getInstance().apply {
                timeInMillis = millis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val diffMillis = dueDay.timeInMillis - baseDay.timeInMillis
            val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()

            return when {
                diffDays <= 0 -> 5      // 当日、または過ぎている
                diffDays == 1 -> 4      // 明日
                diffDays == 2 -> 3      // 明後日
                else -> basePriority    // それ以外は設定した基本優先度
            }.coerceIn(1, 5)
        }

        val realPriority = calculatePriorityFor(deadlineMillis)
        return if (targetDeadlineMillis != null) {
            val targetPriority = calculatePriorityFor(targetDeadlineMillis)
            maxOf(realPriority, targetPriority)
        } else {
            realPriority
        }
    }
}