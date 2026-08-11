package com.example.voicevox

import kotlinx.serialization.Serializable

// アラーム1件分の情報をまとめておくデータクラスです
@Serializable
data class AlarmItem(
    val id: String,          // アラームを識別するための固有ID
    var hour: Int,           // 時
    var minute: Int,         // 分
    var message: String,     // セリフ
    var speakerId: Int,      // VOICEVOXの話者ID
    var speakerName: String, // 画面表示用のキャラクター名
    var isEnabled: Boolean,  // アラームのON/OFF状態
    var readTasks: Boolean,  // タスク読み上げを行うかどうか
    var vibrate: Boolean = true, // バイブレーションを有効にするかどうか
    var repeatDays: List<Int> = emptyList(), // 繰り返し曜日 (1=日, 2=月, ..., 7=土)
    var isOneShot: Boolean = false // 一度鳴ったら削除するかどうか
)
