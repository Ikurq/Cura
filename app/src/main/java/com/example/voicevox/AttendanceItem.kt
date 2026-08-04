package com.example.voicevox

import kotlinx.serialization.Serializable

@Serializable
enum class AttendanceStatus {
    NONE,
    ATTEND,
    ABSENT,
    LATE;

    companion object {
        fun fromString(value: String): AttendanceStatus {
            return entries.find { it.name == value } ?: NONE
        }
    }
}

@Serializable
data class SubjectStats(
    val name: String,
    var totalScheduled: Int = 0,
    var attended: Int = 0,
    var absent: Int = 0,
    var late: Int = 0,
    val absentDates: MutableList<String> = mutableListOf()
)
