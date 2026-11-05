package com.example.voicevox

import android.content.Context

/**
 * 選択中キャラクターのテーマ（カラー・アセット）を一括管理するマネージャー。
 * 今後新キャラクターを追加する際は、PRESETS に定義を追加することで対応可能です。
 */
object CharacterThemeManager {
    private const val PREFS_NAME = "CharacterThemePrefs"
    private const val KEY_SELECTED_CHARACTER_ID = "selected_character_id"

    /**
     * 登録済みキャラクターテーマのプリセット一覧
     */
    val PRESETS = listOf(
        CharacterTheme(
            id = "cura",
            name = "キュラ",
            archetype = "放課後の教室 / スクールマネージャー",
            dialogueAsset = "cura_messages.json",
            standingImageRes = R.drawable.guardian_character,
            iconRes = R.drawable.cura_icon_character,
            gradientStartHex = "#1E2235",
            gradientEndHex = "#111420",
            signatureColorHex = "#38BDF8", // スカイブルー（キュラ固有色）
            taskColorHex = "#FB7185",      // コーラルピンク（宿題・タスク）
            scheduleColorHex = "#34D399",  // ミントグリーン（時間割・予定）
            cornerRadiusDp = 8
        ),
        CharacterTheme(
            id = "night_archive",
            name = "シオン (構想サンプル)",
            archetype = "深夜の書斎 / デジタルアーカイブ",
            dialogueAsset = "cura_messages.json",
            standingImageRes = R.drawable.guardian_character_casual,
            iconRes = R.drawable.cura_icon_character,
            gradientStartHex = "#0F172A",
            gradientEndHex = "#020617",
            signatureColorHex = "#10B981", // エメラルド（理系・分析色）
            taskColorHex = "#F59E0B",      // アンバー（重要度別タスク）
            scheduleColorHex = "#06B6D4",  // シアン（ログ照合）
            cornerRadiusDp = 4
        )
    )

    /**
     * 現在選択されているキャラクターのテーマを取得する
     */
    fun getCurrentTheme(context: Context): CharacterTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedId = prefs.getString(KEY_SELECTED_CHARACTER_ID, "cura") ?: "cura"
        return PRESETS.find { it.id == selectedId } ?: PRESETS.first()
    }

    /**
     * キャラクターを切り替える
     */
    fun setCurrentTheme(context: Context, characterId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_CHARACTER_ID, characterId).apply()
    }
}
