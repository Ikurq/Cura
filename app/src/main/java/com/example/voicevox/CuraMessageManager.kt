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

        val json = try {
            val jsonString = context.assets.open(currentAsset).bufferedReader().use { it.readText() }
            JSONObject(jsonString)
        } catch (e: Exception) {
            // 万一指定アセットの読み込みに失敗した場合はデフォルトにフォールバック
            try {
                val fallbackString = context.assets.open("cura_messages.json").bufferedReader().use { it.readText() }
                JSONObject(fallbackString)
            } catch (ex: Exception) {
                JSONObject()
            }
        }

        data = json
        currentLoadedAsset = currentAsset
        return json
    }

    // --- システムログ ---
    fun getSystemLogs(context: Context): List<String> {
        return try {
            val array = loadData(context).optJSONArray("system_logs") ?: return listOf("SYSTEM: ACTIVE")
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            listOf("SYSTEM: ACTIVE")
        }
    }

    fun getBootLogs(context: Context): List<String> {
        return try {
            val array = loadData(context).optJSONArray("boot_logs") ?: return listOf("READY.")
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            listOf("READY.")
        }
    }

    // --- タップ・放置反応 ---
    fun getRandomRapidTapReaction(context: Context): String {
        return try {
            val array = loadData(context).optJSONArray("rapid_tap_reactions")
            if (array != null && array.length() > 0) {
                array.getString((0 until array.length()).random())
            } else "……？"
        } catch (e: Exception) {
            "……？"
        }
    }

    fun getRandomIdleLine(context: Context): String {
        return try {
            val array = loadData(context).optJSONArray("idle_lines")
            if (array != null && array.length() > 0) {
                array.getString((0 until array.length()).random())
            } else "……"
        } catch (e: Exception) {
            "……"
        }
    }

    // --- ストーリー・マイルストーン ---
    fun getMilestoneMessage(context: Context, count: Long): String? {
        return try {
            val milestones = loadData(context).optJSONObject("milestone_messages") ?: return null
            val key = count.toString()
            if (milestones.has(key)) milestones.getString(key) else null
        } catch (e: Exception) {
            null
        }
    }

    fun getLevelStory(context: Context, level: Int): String? {
        return try {
            val stories = loadData(context).optJSONObject("level_stories") ?: return null
            val key = level.toString()
            if (stories.has(key)) stories.getString(key) else null
        } catch (e: Exception) {
            null
        }
    }

    // --- 時間・曜日・季節の挨拶 ---
    fun getRandomGreeting(context: Context): String {
        return try {
            val json = loadData(context)
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val greetings = json.optJSONObject("time_based_greetings") ?: return "本日もよろしくお願いいたします。"

            for (key in greetings.keys()) {
                val period = greetings.optJSONObject(key) ?: continue
                val start = period.optInt("start", 0)
                val end = period.optInt("end", 23)

                val isMatch = if (start <= end) {
                    hour in start..end
                } else {
                    hour >= start || hour <= end
                }

                if (isMatch) {
                    val lines = period.optJSONArray("lines")
                    if (lines != null && lines.length() > 0) {
                        return lines.getString((0 until lines.length()).random())
                    }
                }
            }
            "本日もよろしくお願いいたします。"
        } catch (e: Exception) {
            "本日もよろしくお願いいたします。"
        }
    }

    fun getRandomDayOfWeekLine(context: Context): String? {
        return try {
            val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
            val lines = loadData(context).optJSONObject("day_of_week_lines")?.optJSONArray(dayOfWeek)
            if (lines != null && lines.length() > 0) {
                lines.getString((0 until lines.length()).random())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun getRandomSeasonalLine(context: Context): String? {
        return try {
            val cal = Calendar.getInstance()
            val key = "${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
            val lines = loadData(context).optJSONObject("seasonal_lines")?.optJSONArray(key)
            if (lines != null && lines.length() > 0) {
                lines.getString((0 until lines.length()).random())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // --- 状況別セリフ ---
    fun getSituationalLine(context: Context, key: String): String {
        return try {
            val situational = loadData(context).optJSONObject("situational_lines") ?: return "..."
            val value = situational.opt(key) ?: return "..."
            if (value is org.json.JSONArray) {
                if (value.length() > 0) {
                    (0 until value.length()).map { value.getString(it) }.random()
                } else "..."
            } else {
                value.toString()
            }
        } catch (e: Exception) {
            "..."
        }
    }

    fun getNextEventReminder(context: Context, startTime: String, title: String): String {
        return try {
            val format = loadData(context).optJSONObject("situational_lines")?.optString("next_event_reminder", "まもなく %s より【%s】の時間です。")
                ?: "まもなく %s より【%s】の時間です。"
            String.format(format, startTime, title)
        } catch (e: Exception) {
            "まもなく $startTime より【$title】の時間です。"
        }
    }

    // --- マジックナンバー ---
    fun getIntConstant(context: Context, key: String, defaultValue: Int): Int {
        return try {
            loadData(context).optJSONObject("magic_numbers")?.optInt(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            defaultValue
        }
    }

    fun getExpPerLevel(context: Context): Int {
        return getIntConstant(context, "exp_per_level", 100)
    }

    fun getMemoryUnlockTapCount(context: Context): Long {
        return try {
            loadData(context).optJSONObject("magic_numbers")?.optLong("memory_unlock_tap_count", 300L) ?: 300L
        } catch (e: Exception) {
            300L
        }
    }
}