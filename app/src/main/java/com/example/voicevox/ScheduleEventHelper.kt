package com.example.voicevox

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ScheduleEventHelper {

    val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * イベントの表示用時刻文字列を生成する。
     * - 終日の場合: "終日"
     * - 終了時刻が指定されている場合: "09:00〜10:30"
     * - 開始時刻のみの場合: "09:00"
     */
    fun formatTimeDisplay(startTime: Long, endTime: Long?, isAllDay: Boolean): String {
        if (isAllDay) return "終日"
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return if (endTime != null && endTime > startTime) {
            "${sdf.format(Date(startTime))}〜${sdf.format(Date(endTime))}"
        } else {
            sdf.format(Date(startTime))
        }
    }

    /**
     * カード上の終了時刻テキスト（例: "〜10:30"）を生成する。
     */
    fun formatEndTimeLabel(endTime: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "〜${sdf.format(Date(endTime))}"
    }

    /**
     * ソート用の分数値を算出する。
     * 終日イベントは最上位に表示するため負の値 (-1L) を返す。
     */
    fun calculateSortTime(startTime: Long, isAllDay: Boolean): Long {
        if (isAllDay) return -1L
        val cal = Calendar.getInstance().apply { timeInMillis = startTime }
        return cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
    }

    /**
     * JSON文字列から安全に ScheduleEvent リストをデコードする。
     * - 過去バージョンの "genre" キーにも自動対応
     * - 一部の要素が破損していても、正常な要素を可能な限り救出
     * - 例外で全体が落ちることを防ぐ
     */
    fun decodeEventsSafely(jsonString: String?): List<ScheduleEvent> {
        if (jsonString.isNullOrBlank() || jsonString.trim() == "[]") {
            return emptyList()
        }

        // まず一括パースを試みる
        try {
            return jsonParser.decodeFromString<List<ScheduleEvent>>(jsonString)
        } catch (_: Exception) {
            // 一括パース失敗時は要素単位のサルベージを試みる
        }

        return recoverValidEvents(jsonString)
    }

    /**
     * 破損要素を含む可能性があるJSONから、正常な要素のみを救出する。
     */
    fun recoverValidEvents(jsonString: String): List<ScheduleEvent> {
        val recovered = mutableListOf<ScheduleEvent>()
        try {
            val jsonTree = jsonParser.parseToJsonElement(jsonString)
            if (jsonTree is JsonArray) {
                for (element in jsonTree) {
                    try {
                        val event = jsonParser.decodeFromJsonElement<ScheduleEvent>(element)
                        recovered.add(event)
                    } catch (_: Exception) {
                        // 破損要素をスキップ
                    }
                }
            }
        } catch (_: Exception) {
            // JSON構文自体の破損
        }
        return recovered
    }

    /**
     * イベントリストをJSON文字列にエンコードする。
     */
    fun encodeEvents(events: List<ScheduleEvent>): String {
        return jsonParser.encodeToString(events)
    }

    /**
     * 旧形式の "genre": キーを含むJSONを "summary": に変換する。
     */
    fun migrateLegacyJson(jsonString: String): String {
        return if (jsonString.contains("\"genre\":")) {
            jsonString.replace("\"genre\":", "\"summary\":")
        } else {
            jsonString
        }
    }

    /**
     * SharedPreferences に安全に予定を保存する。
     * - 既存データが存在する場合、常にバックアップキーに待避してから保存する。
     */
    fun saveEvents(context: Context, events: List<ScheduleEvent>) {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val oldJson = prefs.getString(CuraConstants.KEY_EVENT_LIST, null)
        val newJson = encodeEvents(events)

        val editor = prefs.edit()
        if (!oldJson.isNullOrBlank() && oldJson != "[]") {
            editor.putString(CuraConstants.KEY_EVENT_LIST_BACKUP, oldJson)
        }
        editor.putString(CuraConstants.KEY_EVENT_LIST, newJson).apply()
    }

    /**
     * アプリ起動時に呼び出され、未マイグレーションのデータ（genre キー）を自動修復する。
     */
    fun performAutoMigration(context: Context) {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val currentJson = prefs.getString(CuraConstants.KEY_EVENT_LIST, null) ?: return

        if (currentJson.contains("\"genre\":")) {
            prefs.edit()
                .putString(CuraConstants.KEY_EVENT_LIST_BACKUP, currentJson)
                .putString(CuraConstants.KEY_EVENT_LIST, migrateLegacyJson(currentJson))
                .apply()
        }
    }
}
