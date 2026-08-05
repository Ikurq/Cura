package com.example.voicevox

import kotlinx.serialization.Serializable

@Serializable
data class ScheduleEvent(
    val id: String,
    val summary: String, // 以前は genre だったが summary に統一
    val startTime: Long, // Epoch millis
    val location: String,
    val isPreset: Boolean = false,
    val isAttendanceTracked: Boolean = false,
    var attendanceStatus: String = "NONE"
)

@Serializable
data class EventPreset(
    val genre: String, // UI上のラベル名として genre を維持
    val location: String,
    val hour: Int = -1,
    val minute: Int = -1
)
