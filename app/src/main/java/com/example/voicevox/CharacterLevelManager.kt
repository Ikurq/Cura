package com.example.voicevox

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class CharacterLevelInfo(
    val level: Int,
    val currentExpInLevel: Long,
    val requiredExpForNextLevel: Long
)

/**
 * キャラクターごとのレベルおよび経験値（EXP）を管理するマネージャー。
 * 各キャラクター（cura, charlotte, neko, shion など）ごとに独立した経験値を保持します。
 * 従来の全体共通 totalExp からの自動マイグレーションに対応しています。
 */
object CharacterLevelManager {

    const val DEFAULT_CHARACTER_ID = "cura"
    const val DEFAULT_EXP_PER_LEVEL = 100L

    /**
     * キャラクターごとの総EXPを保存するキーを生成
     */
    fun getTotalExpKey(characterId: String): String {
        val cleanId = characterId.trim().lowercase().ifEmpty { DEFAULT_CHARACTER_ID }
        return "${CuraConstants.KEY_TOTAL_EXP}_$cleanId"
    }

    /**
     * キャラクターごとのストーリー既読レベルを保存するキーを生成
     */
    fun getStorySeenLevelKey(characterId: String): String {
        val cleanId = characterId.trim().lowercase().ifEmpty { DEFAULT_CHARACTER_ID }
        return "last_seen_story_lv_$cleanId"
    }

    /**
     * 指定されたキャラクター（省略時は現在のアクティブキャラクター）の総EXPを取得
     */
    fun getTotalExp(context: Context, characterId: String? = null): Long {
        val targetCharId = characterId ?: CharacterThemeManager.getCurrentTheme(context).id
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_CHARACTER, Context.MODE_PRIVATE)
        return getTotalExpFromPrefs(prefs, targetCharId)
    }

    /**
     * SharedPreferences から直接総EXPを取得
     */
    fun getTotalExpFromPrefs(prefs: SharedPreferences, characterId: String): Long {
        val cleanId = characterId.trim().lowercase().ifEmpty { DEFAULT_CHARACTER_ID }
        val specificKey = getTotalExpKey(cleanId)
        if (prefs.contains(specificKey)) {
            return prefs.getLong(specificKey, 0L)
        }

        // マイグレーション: 旧キー "totalExp" が存在し、デフォルトキャラクター(cura)の場合は旧キーの値を継承
        val legacyExp = prefs.getLong(CuraConstants.KEY_TOTAL_EXP, -1L)
        if (legacyExp >= 0L && cleanId == DEFAULT_CHARACTER_ID) {
            prefs.edit { putLong(specificKey, legacyExp) }
            return legacyExp
        }

        return 0L
    }

    /**
     * 指定キャラクターの総EXPを設定
     */
    fun setTotalExp(context: Context, exp: Long, characterId: String? = null) {
        val targetCharId = characterId ?: CharacterThemeManager.getCurrentTheme(context).id
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_CHARACTER, Context.MODE_PRIVATE)
        setTotalExpInPrefs(prefs, targetCharId, exp)
    }

    /**
     * SharedPreferences に直接総EXPを設定
     */
    fun setTotalExpInPrefs(prefs: SharedPreferences, characterId: String, exp: Long) {
        val cleanId = characterId.trim().lowercase().ifEmpty { DEFAULT_CHARACTER_ID }
        val specificKey = getTotalExpKey(cleanId)
        val validExp = exp.coerceAtLeast(0L)
        prefs.edit {
            putLong(specificKey, validExp)
            // デフォルトキャラクターの場合は旧キーも更新して互換性を維持
            if (cleanId == DEFAULT_CHARACTER_ID) {
                putLong(CuraConstants.KEY_TOTAL_EXP, validExp)
            }
        }
    }

    /**
     * 指定キャラクターにEXPを加算
     */
    fun addTotalExp(context: Context, amount: Long, characterId: String? = null): Long {
        val targetCharId = characterId ?: CharacterThemeManager.getCurrentTheme(context).id
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_CHARACTER, Context.MODE_PRIVATE)
        val current = getTotalExpFromPrefs(prefs, targetCharId)
        val newTotal = (current + amount).coerceAtLeast(0L)
        setTotalExpInPrefs(prefs, targetCharId, newTotal)
        return newTotal
    }

    /**
     * 経験値からレベル、現レベル内の経験値、次レベルまでに必要な経験値を計算
     */
    fun calculateLevelInfo(exp: Long, expPerLevel: Long = DEFAULT_EXP_PER_LEVEL): CharacterLevelInfo {
        val safeExpPerLevel = expPerLevel.coerceAtLeast(1L)
        val safeExp = exp.coerceAtLeast(0L)
        val lv = (safeExp / safeExpPerLevel).toInt() + 1
        val currentLevelBaseExp = (lv - 1) * safeExpPerLevel
        val currentExpInLevel = safeExp - currentLevelBaseExp
        return CharacterLevelInfo(
            level = lv,
            currentExpInLevel = currentExpInLevel,
            requiredExpForNextLevel = safeExpPerLevel
        )
    }

    /**
     * 経験値からレベルのみを計算
     */
    fun calculateLevel(exp: Long, expPerLevel: Long = DEFAULT_EXP_PER_LEVEL): Int {
        return calculateLevelInfo(exp, expPerLevel).level
    }

    /**
     * ストーリー既読レベルの取得
     */
    fun getLastSeenStoryLevel(context: Context, characterId: String? = null): Int {
        val targetCharId = characterId ?: CharacterThemeManager.getCurrentTheme(context).id
        val cleanId = targetCharId.trim().lowercase().ifEmpty { DEFAULT_CHARACTER_ID }
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_CHARACTER, Context.MODE_PRIVATE)
        val specificKey = getStorySeenLevelKey(cleanId)
        if (prefs.contains(specificKey)) {
            return prefs.getInt(specificKey, 0)
        }
        if (cleanId == DEFAULT_CHARACTER_ID) {
            return prefs.getInt("last_seen_story_lv", 0)
        }
        return 0
    }

    /**
     * ストーリー既読レベルの更新
     */
    fun setLastSeenStoryLevel(context: Context, level: Int, characterId: String? = null) {
        val targetCharId = characterId ?: CharacterThemeManager.getCurrentTheme(context).id
        val cleanId = targetCharId.trim().lowercase().ifEmpty { DEFAULT_CHARACTER_ID }
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_CHARACTER, Context.MODE_PRIVATE)
        val specificKey = getStorySeenLevelKey(cleanId)
        prefs.edit {
            putInt(specificKey, level)
            if (cleanId == DEFAULT_CHARACTER_ID) {
                putInt("last_seen_story_lv", level)
            }
        }
    }
}
