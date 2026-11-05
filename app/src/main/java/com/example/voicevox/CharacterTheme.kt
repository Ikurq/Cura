package com.example.voicevox

import androidx.annotation.DrawableRes

/**
 * ナビゲーションバーの各タブアイコンをキャラクターごとに定義するデータクラス
 */
data class CharacterNavIcons(
    @get:DrawableRes val homeIconRes: Int,
    @get:DrawableRes val alarmIconRes: Int,
    @get:DrawableRes val taskIconRes: Int,
    @get:DrawableRes val timetableIconRes: Int,
    @get:DrawableRes val attendanceIconRes: Int
)

/**
 * ボトムナビゲーションの形状、インジケーター形状、カラーを定義するデータクラス
 */
data class CharacterNavStyle(
    val bulgeCornerRadiusDp: Int,         // インジケーターの角丸 (35dp=円形リキッド, 14dp=手帳インデックスタブ, 4dp=硬質)
    val bulgeBackgroundColorHex: String,   // インジケーターの背景色
    val activeItemColorHex: String,        // 選択中アイコン・テキスト色
    val inactiveItemColorHex: String = "#64748B" // 未選択アイコン・テキスト色
)

/**
 * キャラクター固有のUIテーマ、配色、形状、ビジュアルアセットを定義するデータクラス。
 * 画面の骨格を維持したまま、キャラクターごとにUIとナビゲーションを着せ替える基盤となります。
 */
data class CharacterTheme(
    val id: String,
    val name: String,
    val archetype: String,
    val dialogueAsset: String,
    @get:DrawableRes val standingImageRes: Int,
    @get:DrawableRes val iconRes: Int,
    val gradientStartHex: String,
    val gradientEndHex: String,
    val signatureColorHex: String,
    val taskColorHex: String,
    val scheduleColorHex: String,
    val cornerRadiusDp: Int = 8,
    val navStyle: CharacterNavStyle,
    val navIcons: CharacterNavIcons
)
