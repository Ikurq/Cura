package com.example.voicevox

import androidx.annotation.DrawableRes

/**
 * キャラクター固有のUIテーマ、配色、ビジュアルアセットを定義するデータクラス。
 * 画面の骨格（左右分割）を維持したまま、キャラクターごとにUIを着せ替える基盤となります。
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
    val cornerRadiusDp: Int = 8
)
