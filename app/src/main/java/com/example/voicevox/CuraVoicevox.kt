package com.example.voicevox

import android.content.Context
import java.io.File
import java.security.MessageDigest
import jp.voicevox.android.Voicevox
import jp.voicevox.android.VoicevoxCatalog
import jp.voicevox.android.VoicevoxException
import jp.voicevox.android.VoicevoxModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 端末内で完結する音声合成。VOICEVOX CORE を vv-mobile 経由で呼ぶ。
 *
 * 旧 WebVoicevoxClient は su-shiki の Web API を叩いていたため、APIキーとネットワークが
 * 必須だった。こちらは音声モデル(.vvm)さえ落としてあればオフラインで合成できる。
 *
 * 話者の指定に使う `speakerId` は VOICEVOX のスタイルIDで、**Web API 版と同じ値**。
 * 保存済みのアラーム(AlarmItem.speakerId)はそのまま読める。
 */
object CuraVoicevox {

    /** スピナー1行分の声。 */
    data class Voice(
        val styleId: Int,
        val characterName: String,
        val styleName: String,
        val modelId: String,
    ) {
        /** ノーマルは冗長なのでキャラクター名だけにする。 */
        val displayName: String
            get() = if (styleName == "ノーマル") characterName else "$characterName（$styleName）"
    }

    /** 合成の結果。失敗理由で呼び出し側の出しわけが変わるので sealed にしてある。 */
    sealed class SynthesisResult {
        object Success : SynthesisResult()

        /** モデルが未ダウンロード。設定画面へ誘導する。 */
        data class ModelMissing(val modelId: String, val characterName: String) : SynthesisResult()

        /** licenses.json に無いスタイルID(モデル定義が変わった等)。 */
        data class UnknownVoice(val styleId: Int) : SynthesisResult()

        data class Failed(val cause: Throwable) : SynthesisResult()
    }

    /** 既定の話者。四国めたんではなくずんだもん(旧実装のデフォルトと同じ)。 */
    const val DEFAULT_SPEAKER_ID = 3

    private val initMutex = Mutex()

    @Volatile
    private var engineRef: Voicevox? = null

    // --- カタログ(ネイティブ初期化を伴わない) ---

    /** モデル一覧。ONNX Runtime のロードや辞書展開を待たずに読める。 */
    fun catalog(context: Context): VoicevoxCatalog = VoicevoxCatalog.load(context)

    /** 全モデル(ダウンロード状態付き)。 */
    fun models(context: Context): List<VoicevoxModelInfo> = catalog(context).models()

    /**
     * ダウンロード済みモデルに含まれる、読み上げに使える声の一覧。
     * 未ダウンロードの声は選べても合成できないので、UIの選択肢からは外す。
     */
    fun availableVoices(context: Context): List<Voice> =
        models(context).filter { it.isDownloaded }.flatMap { voicesOf(it) }

    /** モデルに含まれる読み上げ用の声。 */
    fun voicesOf(model: VoicevoxModelInfo): List<Voice> =
        if (!model.supportsTalk) {
            emptyList()
        } else {
            model.characters.flatMap { character ->
                character.talkStyles.map { style ->
                    Voice(style.id, character.name, style.name, model.id)
                }
            }
        }

    /** スタイルIDから声を引く。未ダウンロードでも引ける。 */
    fun voiceFor(context: Context, styleId: Int): Voice? =
        catalog(context).modelForStyle(styleId)?.let { model ->
            voicesOf(model).firstOrNull { it.styleId == styleId }
        }

    /** 表示用のキャラクター名。未知のIDなら null。 */
    fun characterNameFor(context: Context, styleId: Int): String? =
        voiceFor(context, styleId)?.characterName

    /** 生成音声を利用する際に必要なクレジット表記(例: 「VOICEVOX:ずんだもん」)。 */
    fun creditTexts(context: Context): List<String> =
        models(context).filter { it.isDownloaded }
            .flatMap { model -> model.characters.map { it.creditText } }
            .distinct()
            .sorted()

    // --- エンジン ---

    /**
     * 合成エンジン。初回は Open JTalk 辞書(約100MB)の展開と ONNX Runtime のロードが走るので
     * 数秒〜数十秒かかる。一覧表示だけなら [catalog] を使うこと。
     */
    suspend fun engine(context: Context): Voicevox {
        engineRef?.let { return it }
        return initMutex.withLock {
            engineRef ?: Voicevox.create(context.applicationContext).also { engineRef = it }
        }
    }

    // --- 合成 ---

    /**
     * テキストを合成して [outputFile] に WAV で書き出す。
     *
     * 同じ (テキスト, 話者) の組はキャッシュから再利用する。キャッシュは
     * `filesDir/voice_cache/` に置き、設定画面のストレージ管理から消せる。
     */
    suspend fun synthesizeToFile(
        context: Context,
        text: String,
        speakerId: Int,
        outputFile: File,
        useCache: Boolean = true,
    ): SynthesisResult {
        val appContext = context.applicationContext
        val cacheFile = cacheFileFor(appContext, text, speakerId)

        if (useCache && cacheFile.exists() && cacheFile.length() > 0) {
            return runCatching {
                withContext(Dispatchers.IO) { cacheFile.copyTo(outputFile, overwrite = true) }
                SynthesisResult.Success
            }.getOrElse { SynthesisResult.Failed(it) }
        }

        val voice = voiceFor(appContext, speakerId)
            ?: return SynthesisResult.UnknownVoice(speakerId)

        return try {
            val wav = engine(appContext).synthesis(text, voice.modelId, speakerId)
            withContext(Dispatchers.IO) {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeBytes(wav)
                cacheFile.copyTo(outputFile, overwrite = true)
            }
            SynthesisResult.Success
        } catch (e: VoicevoxException.ModelNotDownloaded) {
            SynthesisResult.ModelMissing(voice.modelId, voice.characterName)
        } catch (e: VoicevoxException.LicenseNotAccepted) {
            // 同意はダウンロード時に取る。ここに来るのは同意だけ取り消された場合。
            SynthesisResult.ModelMissing(voice.modelId, voice.characterName)
        } catch (e: Exception) {
            SynthesisResult.Failed(e)
        }
    }

    // --- キャッシュ ---

    /** 音声キャッシュの置き場。 */
    fun cacheDir(context: Context): File =
        File(context.applicationContext.filesDir, "voice_cache")

    private fun cacheFileFor(context: Context, text: String, speakerId: Int): File =
        File(cacheDir(context), "cache_${md5("ondevice|$text|$speakerId")}.wav")

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
