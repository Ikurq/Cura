package com.example.voicevox.core.ics

import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.model.IcsEvent
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime

/**
 * iCalendar (RFC 5545) の最小パーサ。VEVENT の SUMMARY / LOCATION / DTSTART / DTEND だけを読む。
 *
 * Android 版の IcsParser の移植。SimpleDateFormat を使わない形に書き直してあるが、
 * 受け付ける書式と、壊れた行を黙って捨てる挙動はそのまま。
 */
class IcsParser {

    fun parse(text: String): List<IcsEvent> = parse(text.split("\r\n", "\n", "\r"))

    fun parse(lines: List<String>): List<IcsEvent> {
        val events = mutableListOf<IcsEvent>()
        var inEvent = false
        var summary = ""
        var start = 0L
        var end = 0L
        var location = ""

        for (line in unfold(lines)) {
            when {
                line.startsWith("BEGIN:VEVENT") -> {
                    inEvent = true
                    summary = ""
                    start = 0L
                    end = 0L
                    location = ""
                }

                line.startsWith("END:VEVENT") -> {
                    if (inEvent) {
                        events.add(IcsEvent(summary, start, end, location))
                        inEvent = false
                    }
                }

                inEvent -> {
                    val key = line.substringBefore(":").substringBefore(";")
                    val value = line.substringAfter(":", "")
                    when (key) {
                        "SUMMARY" -> summary = decodeValue(value)
                        "LOCATION" -> location = decodeValue(value)
                        "DTSTART" -> start = parseDate(value)
                        "DTEND" -> end = parseDate(value)
                    }
                }
            }
        }
        return events
    }

    private fun decodeValue(value: String): String =
        value.replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")

    /**
     * 折り返された行(先頭が空白 or タブ)を直前の行に連結する。
     * RFC 5545 の folding。
     */
    private fun unfold(lines: List<String>): List<String> {
        val unfolded = mutableListOf<String>()
        for (line in lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) {
                unfolded[unfolded.lastIndex] = unfolded.last() + line.substring(1)
            } else {
                unfolded.add(line)
            }
        }
        return unfolded
    }

    /**
     * `20231027T100000Z`(UTC)/ `20231027T100000`(ローカル)/ `20231027`(終日)。
     * 解釈できないものは 0 を返す。
     */
    private fun parseDate(raw: String): Long {
        val value = raw.trim()
        return try {
            when {
                value.endsWith("Z") && value.length >= 16 ->
                    Instant.parse(
                        "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}" +
                            "T${value.substring(9, 11)}:${value.substring(11, 13)}:${value.substring(13, 15)}Z"
                    ).toEpochMilliseconds()

                value.length >= 15 -> CuraTime.toMillis(
                    LocalDateTime(
                        value.substring(0, 4).toInt(),
                        value.substring(4, 6).toInt(),
                        value.substring(6, 8).toInt(),
                        value.substring(9, 11).toInt(),
                        value.substring(11, 13).toInt(),
                        value.substring(13, 15).toInt(),
                    )
                )

                value.length == 8 -> CuraTime.toMillis(
                    LocalDateTime(
                        value.substring(0, 4).toInt(),
                        value.substring(4, 6).toInt(),
                        value.substring(6, 8).toInt(),
                        0, 0, 0,
                    )
                )

                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
