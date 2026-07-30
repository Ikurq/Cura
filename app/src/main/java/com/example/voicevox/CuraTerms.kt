package com.example.voicevox

import android.content.Context
import org.json.JSONObject

/**
 * 各キャラクターの利用規約URLを管理するクラス。
 * assets/character_metadata.json から読み込みます。
 */
object CuraTerms {
    private var metadata: JSONObject? = null

    private fun loadMetadata(context: Context): JSONObject {
        metadata?.let { return it }
        val jsonString = context.assets.open("character_metadata.json").bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)
        metadata = json
        return json
    }

    /**
     * 指定されたキャラクター名の規約URLを取得する。
     * 見つからない場合はVOICEVOX共通規約を返す。
     */
    fun getUrl(context: Context, charName: String): String {
        val json = loadMetadata(context)
        val termsMap = json.getJSONObject("character_terms")
        return if (termsMap.has(charName)) {
            termsMap.getString(charName)
        } else {
            json.getString("common_terms_url")
        }
    }

    /**
     * 共通規約URLを取得する。
     */
    fun getCommonUrl(context: Context): String {
        return loadMetadata(context).getString("common_terms_url")
    }

    /**
     * 指定されたキャラクターが所属するグループのメンバーリストを取得する。
     */
    fun getGroupMembers(context: Context, charName: String): List<String> {
        val json = loadMetadata(context)
        val groups = json.getJSONObject("character_groups")
        
        for (key in groups.keys()) {
            val group = groups.getJSONObject(key)
            val members = group.getJSONArray("members")
            val memberList = mutableListOf<String>()
            var found = false
            for (i in 0 until members.length()) {
                val member = members.getString(i)
                memberList.add(member)
                if (member == charName) found = true
            }
            if (found) return memberList
        }
        return emptyList()
    }
}
