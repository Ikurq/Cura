package com.example.voicevox

import androidx.annotation.DrawableRes
import androidx.annotation.FontRes

/**
 * キャラクター固有のフォント設定を定義するデータクラス
 */
data class CharacterFontConfig(
    val bodyFontName: String = "sans-serif",    // 基本UI書体 ("sans-serif", "sans-serif-medium", "monospace", "serif", "casual")
    val headerFontName: String = "sans-serif",  // 名前・見出し・メッセージ用書体 ("serif", "sans-serif-medium" など)
    @get:FontRes val customFontRes: Int? = null, // res/font/xxx.ttf がある場合のカスタムフォントリソース
    val letterSpacing: Float = 0.02f,           // 文字間隔
    val isBoldHeader: Boolean = true            // 見出し太字フラグ
)

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
 * ボトムナビゲーションのカラーおよびスタイルを定義するデータクラス。
 * 移動図形（バルジ）は全キャラクター共通で美しい正円（サークル）に固定されます。
 * ダークUIでの視認性向上のため、ストローク（輪郭線）および発光シャドウをサポートします。
 */
data class CharacterNavStyle(
    val navBackgroundColorHex: String = "#2A364F", // ナビバーと移動円のサーフェス色
    val activeItemColorHex: String,                // 選択中アイコン・テキスト色
    val inactiveItemColorHex: String = "#64748B",  // 未選択アイコン・テキスト色
    val bulgeCornerRadiusDp: Int = 35,             // インジケーターの角丸（正円: 35dp）
    val strokeColorHex: String? = null,            // 移動円の境界線色（未指定時は activeItemColorHex を使用）
    val strokeWidthDp: Float = 1.5f                // 移動円の境界線幅
)

/**
 * キャラクター固有のUIテーマ、配色、形状、フォント、ビジュアルアセットを定義するデータクラス。
 * 画面の骨格を維持したまま、キャラクターごとにUIとナビゲーション、フォントを着せ替える基盤となります。
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
    val navIcons: CharacterNavIcons,
    val fontConfig: CharacterFontConfig = CharacterFontConfig()
)
