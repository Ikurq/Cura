package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ScheduleExporter {

    private const val PROD_ID = "-//Cura:CareYou//Schedule//JA"

    /**
     * スケジュールイベントリストを iCalendar (RFC 5545) 形式の文字列に変換する。
     */
    fun exportToIcs(events: List<ScheduleEvent>, nowMillis: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:$PROD_ID\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        sb.append("METHOD:PUBLISH\r\n")

        val sdfUtc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dtStamp = sdfUtc.format(Date(nowMillis))

        val sdfLocalTime = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        val sdfDateOnly = SimpleDateFormat("yyyyMMdd", Locale.US)

        events.forEach { event ->
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:${event.id}@curacareyou\r\n")
            sb.append("DTSTAMP:$dtStamp\r\n")
            sb.append("SUMMARY:${escapeIcs(event.summary)}\r\n")

            if (event.location.isNotEmpty()) {
                sb.append("LOCATION:${escapeIcs(event.location)}\r\n")
            }

            if (event.isAllDay) {
                val startDay = sdfDateOnly.format(Date(event.startTime))
                val endCal = Calendar.getInstance().apply {
                    timeInMillis = event.startTime
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                val nextDay = sdfDateOnly.format(endCal.time)
                sb.append("DTSTART;VALUE=DATE:$startDay\r\n")
                sb.append("DTEND;VALUE=DATE:$nextDay\r\n")
            } else {
                sb.append("DTSTART:${sdfLocalTime.format(Date(event.startTime))}\r\n")
                val endTime = if (event.endTime != null && event.endTime > event.startTime) {
                    event.endTime
                } else {
                    event.startTime + (60 * 60 * 1000L) // 1時間後
                }
                sb.append("DTEND:${sdfLocalTime.format(Date(endTime))}\r\n")
            }

            sb.append("STATUS:CONFIRMED\r\n")
            sb.append("END:VEVENT\r\n")
        }

        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /**
     * 指定されたUri（SAFのCreateDocumentで作成されたUri）へICS文字列を出力する。
     */
    fun writeIcsToUri(context: Context, uri: Uri, icsContent: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(icsContent.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * キャッシュディレクトリに一時ICSファイルを作成し、他アプリ共有用のIntentを生成する。
     */
    fun createShareIntent(context: Context, icsContent: String): Intent {
        val cacheFile = File(context.cacheDir, "cura_schedule.ics")
        cacheFile.writeText(icsContent, Charsets.UTF_8)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cura Schedule")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * エクスポート方法（ファイル保存 / 他アプリ共有）の選択ダイアログを表示する。
     */
    fun showExportOptionDialog(context: Context, onSaveToFile: () -> Unit, onShare: () -> Unit) {
        val options = arrayOf(
            context.getString(R.string.export_method_save_file),
            context.getString(R.string.export_method_share)
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.export_schedule)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onSaveToFile()
                    1 -> onShare()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun escapeIcs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
    }
}
