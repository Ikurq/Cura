package com.example.voicevox

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScheduleEvent(
    val id: String = "",
    // 過去バージョンの genre と現在の summary の両方に対応
    @JsonNames("genre", "summary")
    val summary: String = "",
    val startTime: Long = 0L, // Epoch millis
    val location: String = "",
    val isPreset: Boolean = false,
    val isAttendanceTracked: Boolean = false,
    var attendanceStatus: String = "NONE",
    val repeatGroupId: String? = null,
    val endTime: Long? = null,
    val isAllDay: Boolean = false
)

@Serializable
data class EventPreset(
    val genre: String, // UI上のラベル名として genre を維持
    val location: String,
    val hour: Int = -1,
    val minute: Int = -1,
    val endHour: Int = -1,
    val endMinute: Int = -1,
    val isAllDay: Boolean = false
)
