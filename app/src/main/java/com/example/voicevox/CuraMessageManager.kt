package com.example.voicevox

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * キュラのセリフやシステムメッセージ、定数（マジックナンバー）を一括管理するクラス
 */
object CuraMessageManager {
    private var data: JSONObject? = null

    private fun loadData(context: Context): JSONObject {
        data?.let { return it }
        val jsonString = context.assets.open("cura_messages.json").bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        data = json
        return json
    }

    // --- システムログ ---
    fun getSystemLogs(context: Context): List<String> {
        val array = loadData(context).getJSONArray("system_logs")
        return (0 until array.length()).map { array.getString(it) }
    }

    fun getBootLogs(context: Context): List<String> {
        val array = loadData(context).getJSONArray("boot_logs")
        return (0 until array.length()).map { array.getString(it) }
    }

    // --- タップ・放置反応 ---
    fun getRandomRapidTapReaction(context: Context): String {
        val array = loadData(context).getJSONArray("rapid_tap_reactions")
        return array.getString((0 until array.length()).random())
    }

    fun getRandomIdleLine(context: Context): String {
        val array = loadData(context).getJSONArray("idle_lines")
        return array.getString((0 until array.length()).random())
    }

    // --- ストーリー・マイルストーン ---
    fun getMilestoneMessage(context: Context, count: Long): String? {
        val milestones = loadData(context).getJSONObject("milestone_messages")
        val key = count.toString()
        return if (milestones.has(key)) milestones.getString(key) else null
    }

    fun getLevelStory(context: Context, level: Int): String? {
        val stories = loadData(context).getJSONObject("level_stories")
        val key = level.toString()
        return if (stories.has(key)) stories.getString(key) else null
    }

    // --- 時間・曜日・季節の挨拶 ---
    fun getRandomGreeting(context: Context): String {
        val json = loadData(context)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greetings = json.getJSONObject("time_based_greetings")
        
        // 時間帯判定
        for (key in greetings.keys()) {
            val period = greetings.getJSONObject(key)
            val start = period.getInt("start")
            val end = period.getInt("end")
            
            val isMatch = if (start <= end) {
                hour in start..end
            } else {
                // 日を跨ぐ場合 (例: 19..4)
                hour >= start || hour <= end
            }
            
            if (isMatch) {
                val lines = period.getJSONArray("lines")
                return lines.getString((0 until lines.length()).random())
            }
        }
        return "システム稼働中。本日もよろしくお願いします。"
    }

    fun getRandomDayOfWeekLine(context: Context): String? {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
        val lines = loadData(context).getJSONObject("day_of_week_lines").optJSONArray(dayOfWeek)
        return if (lines != null && lines.length() > 0) {
            lines.getString((0 until lines.length()).random())
        } else null
    }

    fun getRandomSeasonalLine(context: Context): String? {
        val cal = Calendar.getInstance()
        val key = "${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        val lines = loadData(context).getJSONObject("seasonal_lines").optJSONArray(key)
        return if (lines != null && lines.length() > 0) {
            lines.getString((0 until lines.length()).random())
        } else null
    }

    // --- マジックナンバー ---
    fun getIntConstant(context: Context, key: String, defaultValue: Int): Int {
        return loadData(context).getJSONObject("magic_numbers").optInt(key, defaultValue)
    }
}
