package com.example.voicevox

data class ScheduleEvent(
    val id: String,
    val genre: String,
    val startTime: Long, // Epoch millis
    val location: String,
    val isPreset: Boolean = false,
    val isAttendanceTracked: Boolean = false,
    var attendanceStatus: String = "NONE" // "NONE", "ATTEND", "ABSENT", "LATE"
)

data class EventPreset(
    val genre: String,
    val location: String,
    val hour: Int = -1,  // -1 means no time stored
    val minute: Int = -1
)