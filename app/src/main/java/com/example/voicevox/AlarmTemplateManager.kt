package com.example.voicevox

import android.content.Context
import org.json.JSONObject

/**
 * アラームや読み上げで使用する文章のテンプレートを assets/alarm_templates.json から読み込むクラス
 */
object AlarmTemplateManager {
    private var data: JSONObject? = null

    private fun loadData(context: Context): JSONObject {
        data?.let { return it }
        val jsonString = context.assets.open("alarm_templates.json").bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        data = json
        return json
    }

    /**
     * 試聴用のテンプレートを取得する
     */
    fun getPreviewTemplate(context: Context): String {
        return loadData(context).getJSONObject("preview").getString("template")
    }

    /**
     * 絶対起きるアラームの生成用テンプレートを取得する
     * 引数: hour, minute, summary, leadTime
     */
    fun getMandatoryAlarmTemplate(context: Context): String {
        return loadData(context).getJSONObject("mandatory_alarm").getString("generation_template")
    }

    /**
     * 起床後の読み上げ（モーニングリーディング）の各パーツを取得する
     */
    fun getMorningTemplate(context: Context, key: String): String {
        return loadData(context).getJSONObject("morning_reading").getString(key)
    }
}
