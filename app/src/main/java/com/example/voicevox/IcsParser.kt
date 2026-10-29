package com.example.voicevox

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class IcsEvent(
    val summary: String = "無題",
    val startTime: Long,
    val endTime: Long = 0L,
    val location: String = "",
    val isAttendanceTracked: Boolean = false,
    val attendanceStatus: String = "NONE",
    val isAllDay: Boolean = false
)

class IcsParser {
    fun parse(lines: List<String>): List<IcsEvent> {
        val events = mutableListOf<IcsEvent>()
        val unfoldedLines = unfold(lines)
        
        var inEvent = false
        var currentSummary = ""
        var currentStart = 0L
        var currentEnd = 0L
        var currentLocation = ""
        var currentIsAllDay = false

        for (line in unfoldedLines) {
            when {
                line.startsWith("BEGIN:VEVENT") -> {
                    inEvent = true
                    currentSummary = ""
                    currentStart = 0L
                    currentEnd = 0L
                    currentLocation = ""
                    currentIsAllDay = false
                }
                line.startsWith("END:VEVENT") -> {
                    if (inEvent) {
                        events.add(IcsEvent(currentSummary, currentStart, currentEnd, currentLocation, isAllDay = currentIsAllDay))
                        inEvent = false
                    }
                }
                inEvent -> {
                    val key = line.substringBefore(":").substringBefore(";")
                    val value = line.substringAfter(":")
                    when (key) {
                        "SUMMARY" -> currentSummary = decodeIcsValue(value)
                        "LOCATION" -> currentLocation = decodeIcsValue(value)
                        "DTSTART" -> {
                            if (line.contains("VALUE=DATE") || value.trim().length == 8) {
                                currentIsAllDay = true
                            }
                            currentStart = parseIcsDate(value)
                        }
                        "DTEND" -> currentEnd = parseIcsDate(value)
                    }
                }
            }
        }
        return events
    }

    private fun decodeIcsValue(value: String): String {
        return value.replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }

    private fun unfold(lines: List<String>): List<String> {
        val unfolded = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (unfolded.isNotEmpty()) {
                    val lastIdx = unfolded.size - 1
                    unfolded[lastIdx] = unfolded[lastIdx] + line.substring(1)
                }
            } else {
                unfolded.add(line)
            }
        }
        return unfolded
    }

    private fun parseIcsDate(dateStr: String): Long {
        return try {
            val trimmed = dateStr.trim()
            if (trimmed.length == 8) {
                val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
                sdf.parse(trimmed)?.time ?: 0L
            } else if (trimmed.endsWith("Z")) {
                val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(trimmed)?.time ?: 0L
            } else {
                val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                sdf.parse(trimmed)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
