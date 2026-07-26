package jp.voicevox.android

import kotlinx.serialization.Serializable

/** 音声モデル(.vvm)1件のメタデータ。licenses.json 由来。 */
@Serializable
data class VoicevoxModelInfo(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val downloadURL: String,
    val vvmId: String,
    /** モデルが対応する合成ドメイン(例: ["talk"]、歌唱モデルは ["frame_decode"])。 */
    val domains: List<String> = listOf("talk"),
    val characters: List<Character>,
    /** listModels() 時に付与されるダウンロード状態。 */
    val isDownloaded: Boolean = false,
) {
    /** テキスト読み上げ(synthesis)に対応したモデルかどうか。 */
    val supportsTalk: Boolean get() = "talk" in domains

    @Serializable
    data class Character(
        val name: String,
        val speakerUuid: String,
        /** 生成音声の利用時に必要なクレジット表記(例: "VOICEVOX:ずんだもん")。 */
        val creditText: String,
        /** キャラクター個別の利用規約URL。 */
        val termsURL: String,
        val styles: List<Style>,
    ) {
        /**
         * @property type スタイル種別。"talk" はテキスト読み上げ(synthesis で使用可)、
         * "frame_decode" は歌唱合成用で synthesis では使えない。
         */
        @Serializable
        data class Style(val name: String, val id: Int, val type: String = "talk")

        /** テキスト読み上げに使えるスタイルのみ。 */
        val talkStyles: List<Style> get() = styles.filter { it.type == "talk" }
    }
}

/** licenses.json 全体。 */
@Serializable
internal data class LicenseCatalog(
    val termsVersion: String,
    val termsURL: String,
    val models: List<VoicevoxModelInfo>,
)
