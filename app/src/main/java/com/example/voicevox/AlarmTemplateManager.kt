package com.example.voicevox

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * 予定連動アラームやモーニングリーディングで使用する文章テンプレートを管理するクラス。
 * assets/alarm_templates.json、またはアプリ領域内の custom_alarm_templates.json から読み込みます。
 */
object AlarmTemplateManager {

    data class AlarmCategory(
        val id: String,
        val name: String,
        val templates: List<String>
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var cachedRoot: JsonObject? = null
    private var testJsonString: String? = null

    /**
     * 単体テスト用にJSON文字列を直接設定します。
     */
    fun setTestJson(jsonStr: String?) {
        testJsonString = jsonStr
        cachedRoot = null
    }

    private fun loadData(context: Context? = null): JsonObject {
        cachedRoot?.let { return it }

        if (testJsonString != null) {
            val root = json.parseToJsonElement(testJsonString!!).jsonObject
            cachedRoot = root
            return root
        }

        if (context == null) {
            return buildJsonObject {}
        }

        val customFile = File(context.filesDir, "custom_alarm_templates.json")
        val jsonString = if (customFile.exists()) {
            try {
                customFile.readText()
            } catch (e: Exception) {
                context.assets.open("alarm_templates.json").bufferedReader().use { it.readText() }
            }
        } else {
            context.assets.open("alarm_templates.json").bufferedReader().use { it.readText() }
        }

        val root = json.parseToJsonElement(jsonString).jsonObject
        cachedRoot = root
        return root
    }

    /**
     * キャッシュをクリアして次回アクセス時に再読み込みさせます。
     */
    fun clearCache() {
        cachedRoot = null
    }

    /**
     * 試聴用のテンプレートを取得する
     */
    fun getPreviewTemplate(context: Context? = null): String {
        return try {
            val preview = loadData(context)["preview"]?.jsonObject
            preview?.get("template")?.jsonPrimitive?.content ?: "これは試聴です。%s"
        } catch (e: Exception) {
            "これは試聴です。%s"
        }
    }

    /**
     * 予定連動アラームの方向性（カテゴリー）一覧を取得する
     */
    fun getMandatoryCategories(context: Context? = null): List<AlarmCategory> {
        val root = loadData(context)
        val mandatory = root["mandatory_alarm"]?.jsonObject ?: return emptyList()
        val categoriesObj = mandatory["categories"]?.jsonObject

        val result = mutableListOf<AlarmCategory>()

        if (categoriesObj != null) {
            for ((key, element) in categoriesObj) {
                when (element) {
                    is JsonObject -> {
                        val name = element["name"]?.jsonPrimitive?.content ?: key
                        val templatesArray = element["templates"]?.jsonArray
                        val list = templatesArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        if (list.isNotEmpty()) {
                            result.add(AlarmCategory(key, name, list))
                        }
                    }
                    is JsonArray -> {
                        val list = element.mapNotNull { it.jsonPrimitive.contentOrNull }
                        if (list.isNotEmpty()) {
                            result.add(AlarmCategory(key, key, list))
                        }
                    }
                    else -> {}
                }
            }
        }

        // カテゴリー未定義だが単体テンプレートがある場合の下位互換
        if (result.isEmpty()) {
            val legacy = mandatory["generation_template"]?.jsonPrimitive?.contentOrNull ?: ""
            if (legacy.isNotEmpty()) {
                result.add(AlarmCategory("standard", "標準", listOf(legacy)))
            }
        }

        return result
    }

    /**
     * 指定された方向性（カテゴリー）からランダムにテンプレートを1つ取得する。
     * categoryId が null または "all" の場合は全カテゴリーからランダムに選出。
     */
    fun getRandomMandatoryTemplate(context: Context? = null, categoryId: String? = null): String {
        val categories = getMandatoryCategories(context)
        if (categories.isEmpty()) {
            return "%d時%d分を過ぎています。%sまであと%d分です。起きてください。"
        }

        if (categoryId != null && categoryId != "all") {
            val found = categories.find { it.id == categoryId }
            if (found != null && found.templates.isNotEmpty()) {
                return found.templates.random()
            }
        }

        val allTemplates = categories.flatMap { it.templates }
        return if (allTemplates.isNotEmpty()) {
            allTemplates.random()
        } else {
            "%d時%d分を過ぎています。%sまであと%d分です。起きてください。"
        }
    }

    /**
     * 予定連動アラームの生成用テンプレートを取得する（後方互換用）
     */
    fun getMandatoryAlarmTemplate(context: Context? = null): String {
        return getRandomMandatoryTemplate(context)
    }

    /**
     * テンプレート文字列に予定情報を埋め込み、読み上げ用メッセージを組み立てる
     *
     * サポートしているプレースホルダー:
     * - {time}: "7時30分"
     * - {hour}: 時 (7)
     * - {minute}: 分 (30)
     * - {event} または {summary}: 予定名
     * - {leadTime}: 何分前か (30)
     *
     * 従来の位置指定書式（%d時%d分... %s... %d分）も自動判定して完全サポートします。
     */
    fun formatMandatoryAlarmMessage(
        template: String,
        hour: Int,
        minute: Int,
        eventSummary: String,
        leadTimeMinutes: Int
    ): String {
        if (template.contains("{")) {
            val timeStr = "${hour}時${minute}分"
            return template
                .replace("{hour}", hour.toString())
                .replace("{minute}", minute.toString())
                .replace("{time}", timeStr)
                .replace("{event}", eventSummary)
                .replace("{summary}", eventSummary)
                .replace("{leadTime}", leadTimeMinutes.toString())
        }

        return try {
            String.format(Locale.getDefault(), template, hour, minute, eventSummary, leadTimeMinutes)
        } catch (e: Exception) {
            "${hour}時${minute}分です。${eventSummary}まであと${leadTimeMinutes}分です。起きてください。"
        }
    }

    /**
     * 起床後の読み上げ（モーニングリーディング）の各パーツを取得する。
     * JSONの値が文字列の配列である場合、その中からランダムに1つ選択して返します。
     */
    fun getMorningTemplate(context: Context? = null, key: String): String {
        val root = loadData(context)
        val morning = root["morning_reading"]?.jsonObject ?: return ""
        val element = morning[key] ?: return ""
        return when (element) {
            is JsonArray -> {
                val list = element.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (list.isNotEmpty()) list.random() else ""
            }
            is JsonPrimitive -> element.content
            else -> ""
        }
    }
}