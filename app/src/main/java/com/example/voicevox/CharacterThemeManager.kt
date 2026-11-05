package com.example.voicevox

import android.content.Context

/**
 * 選択中キャラクターのテーマ（カラー・アセット・ボトムナビ形状・フォント）を一括管理するマネージャー。
 */
object CharacterThemeManager {
    private const val PREFS_NAME = "CharacterThemePrefs"
    private const val KEY_SELECTED_CHARACTER_ID = "selected_character_id"

    // 共通デフォルトアイコンセット
    private val DEFAULT_ICONS = CharacterNavIcons(
        homeIconRes = R.drawable.ic_tech_home,
        alarmIconRes = R.drawable.ic_tech_alarm,
        taskIconRes = R.drawable.ic_tech_task,
        timetableIconRes = R.drawable.ic_tech_schedule,
        attendanceIconRes = R.drawable.ic_tech_attendance
    )

    /**
     * 登録済みキャラクターテーマのプリセット一覧
     */
    val PRESETS = listOf(
        CharacterTheme(
            id = "cura",
            name = "キュラ",
            archetype = "放課後デスク / スクールマネージャー",
            dialogueAsset = "cura_messages.json",
            standingImageRes = R.drawable.guardian_character,
            iconRes = R.drawable.cura_icon_character,
            gradientStartHex = "#1E2235",
            gradientEndHex = "#111420",
            signatureColorHex = "#38BDF8", // スカイブルー（キュラ固有色）
            taskColorHex = "#FB7185",      // コーラルピンク（宿題・タスク）
            scheduleColorHex = "#34D399",  // ミントグリーン（時間割・予定）
            cornerRadiusDp = 8,
            navStyle = CharacterNavStyle(
                bulgeCornerRadiusDp = 35,      // キュラは円形リキッドインジケーター
                navBackgroundColorHex = "#1E2638", // バーと移動円の統一色
                activeItemColorHex = "#38BDF8",
                inactiveItemColorHex = "#64748B"
            ),
            navIcons = DEFAULT_ICONS,
            fontConfig = CharacterFontConfig(
                systemFontName = "sans-serif-medium", // 自然体で整った手帳風ゴシック
                letterSpacing = 0.02f,
                isBoldHeader = true
            )
        ),
        CharacterTheme(
            id = "shion",
            name = "シオン (構想サンプル)",
            archetype = "深夜書斎 / デジタルアーカイブ",
            dialogueAsset = "cura_messages.json",
            standingImageRes = R.drawable.guardian_character_casual,
            iconRes = R.drawable.cura_icon_character,
            gradientStartHex = "#0F172A",
            gradientEndHex = "#020617",
            signatureColorHex = "#10B981", // エメラルド（理系・分析色）
            taskColorHex = "#F59E0B",      // アンバー
            scheduleColorHex = "#06B6D4",  // シアン
            cornerRadiusDp = 4,
            navStyle = CharacterNavStyle(
                bulgeCornerRadiusDp = 4,       // 硬質・シャープな四角形インジケーター
                navBackgroundColorHex = "#11221D", // バーと移動四角形の統一色
                activeItemColorHex = "#10B981",
                inactiveItemColorHex = "#475569"
            ),
            navIcons = DEFAULT_ICONS,
            fontConfig = CharacterFontConfig(
                systemFontName = "monospace", // 冷静で理系的な等幅フォント
                letterSpacing = 0.04f,
                isBoldHeader = false
            )
        ),
        CharacterTheme(
            id = "yuuhi",
            name = "ユウヒ (構想サンプル)",
            archetype = "夕焼け部室 / アスリートマネージャー",
            dialogueAsset = "cura_messages.json",
            standingImageRes = R.drawable.guardian_character_summer,
            iconRes = R.drawable.cura_icon_character,
            gradientStartHex = "#2E1A17",
            gradientEndHex = "#140D0C",
            signatureColorHex = "#F97316", // ウォームオレンジ
            taskColorHex = "#EF4444",      // レッド
            scheduleColorHex = "#FBBF24",  // イエロー
            cornerRadiusDp = 12,
            navStyle = CharacterNavStyle(
                bulgeCornerRadiusDp = 12,      // タブ・ピル型の角丸インジケーター
                navBackgroundColorHex = "#291614", // バーとタブの統一色
                activeItemColorHex = "#F97316",
                inactiveItemColorHex = "#78350F"
            ),
            navIcons = DEFAULT_ICONS,
            fontConfig = CharacterFontConfig(
                systemFontName = "casual", // 親しみやすいカジュアルフォント
                letterSpacing = 0.01f,
                isBoldHeader = true
            )
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
