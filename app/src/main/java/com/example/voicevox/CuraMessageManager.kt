package com.example.voicevox

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * 選択中キャラクターのセリフやシステムメッセージ、定数を動的に一括管理するクラス
 */
object CuraMessageManager {
    private var data: JSONObject? = null
    private var currentLoadedAsset: String? = null

    private fun loadData(context: Context): JSONObject {
        val currentAsset = try {
            CharacterThemeManager.getCurrentTheme(context).dialogueAsset
        } catch (e: Exception) {
            "cura_messages.json"
        }

        if (data != null && currentLoadedAsset == currentAsset) {
            return data!!
        }

        val jsonString = context.assets.open(currentAsset).bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        data = json
        currentLoadedAsset = currentAsset
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
        return "本日もよろしくお願いいたします。"
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

    // --- 状況別セリフ ---
    fun getSituationalLine(context: Context, key: String): String {
        val situational = loadData(context).getJSONObject("situational_lines")
        val value = situational.get(key)
        return if (value is org.json.JSONArray) {
            (0 until value.length()).map { value.getString(it) }.random()
        } else {
            value.toString()
        }
    }

    fun getNextEventReminder(context: Context, startTime: String, title: String): String {
        val format = loadData(context).getJSONObject("situational_lines").getString("next_event_reminder")
        return String.format(format, startTime, title)
    }

    // --- マジックナンバー ---
    fun getIntConstant(context: Context, key: String, defaultValue: Int): Int {
        return loadData(context).getJSONObject("magic_numbers").optInt(key, defaultValue)
    }

    fun getExpPerLevel(context: Context): Int {
        return loadData(context).getJSONObject("magic_numbers").getInt("exp_per_level")
    }

    fun getMemoryUnlockTapCount(context: Context): Long {
        return loadData(context).getJSONObject("magic_numbers").getLong("memory_unlock_tap_count")
    }
}
