package com.example.voicevox

import java.text.SimpleDateFormat
import java.util.*

data class IcsEvent(
    val summary: String,
    val startTime: Long,
    val endTime: Long,
    val location: String = "",
    val isAttendanceTracked: Boolean = false,
    val attendanceStatus: String = "NONE"
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

        for (line in unfoldedLines) {
            when {
                line.startsWith("BEGIN:VEVENT") -> {
                    inEvent = true
                    currentSummary = ""
                    currentStart = 0L
                    currentEnd = 0L
                    currentLocation = ""
                }
                line.startsWith("END:VEVENT") -> {
                    if (inEvent) {
                        events.add(IcsEvent(currentSummary, currentStart, currentEnd, currentLocation))
                        inEvent = false
                    }
                }
                inEvent -> {
                    val key = line.substringBefore(":").substringBefore(";")
                    val value = line.substringAfter(":")
                    when (key) {
                        "SUMMARY" -> currentSummary = decodeIcsValue(value)
                        "LOCATION" -> currentLocation = decodeIcsValue(value)
                        "DTSTART" -> currentStart = parseIcsDate(value)
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
        // Formats: 20231027T100000Z (UTC) or 20231027T100000 (Local)
        return try {
            if (dateStr.endsWith("Z")) {
                val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(dateStr)?.time ?: 0L
            } else {
                val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                sdf.parse(dateStr)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}