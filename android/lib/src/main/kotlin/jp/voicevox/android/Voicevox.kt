package jp.voicevox.android

import android.content.Context
import java.io.File
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 高レベル Facade。通常の利用はこのクラスだけで完結する。
 *
 * ```kotlin
 * val voicevox = Voicevox.create(context)
 *
 * // 1. モデル一覧
 * val models = voicevox.listModels()
 *
 * // 2. 利用規約に同意してダウンロード(個別 or 一括)
 * voicevox.acceptLicense("0")
 * voicevox.downloadModel("0")
 *
 * // 3. 合成
 * val wav = voicevox.synthesis("こんにちは", modelId = "0")
 * ```
 */
class Voicevox private constructor(
    private val catalog: LicenseCatalog,
    val gate: LicenseGate,
    private val manager: ModelManager,
    private val synthesizer: Synthesizer,
    private val maxConcurrentDownloads: Int,
) {
    private val loadedModelIds = mutableSetOf<String>()
    private val loadMutex = Mutex()

    companion object {
        /**
         * Facade を構築する。ネイティブ初期化と辞書展開を含むため suspend。
         *
         * @param context アプリケーションコンテキスト。
         * @param modelsDir モデル保存先(null なら filesDir/voicevox/models)。
         * @param maxConcurrentDownloads 並列ダウンロードの同時実行数(既定4)。
         */
        suspend fun create(
            context: Context,
            modelsDir: File? = null,
            maxConcurrentDownloads: Int = 4,
        ): Voicevox = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val catalog = Json.decodeFromString<LicenseCatalog>(
                appContext.assets.open("licenses.json").bufferedReader().readText()
            )
            val gate = LicenseGate(appContext, catalog.termsVersion)
            val manager = ModelManager(
                catalog = catalog,
                gate = gate,
                modelsDir = modelsDir ?: File(appContext.filesDir, "voicevox/models"),
            )

            val dictDir = extractOpenJtalkDict(appContext)
            val onnxruntime = Onnxruntime.loadOnce().perform()
            val synthesizer = Synthesizer.builder(onnxruntime, OpenJtalk(dictDir.absolutePath)).build()

            Voicevox(catalog, gate, manager, synthesizer, maxConcurrentDownloads.coerceAtLeast(1))
        }

        /** assets の Open JTalk 辞書を filesDir に展開する(展開済みならスキップ)。 */
        private fun extractOpenJtalkDict(context: Context): File {
            val dictDir = File(context.filesDir, "voicevox/open_jtalk_dic")
            val marker = File(dictDir, ".complete")
            if (marker.exists()) return dictDir

            dictDir.mkdirs()
            val assets = context.assets
            for (name in assets.list("open_jtalk_dic").orEmpty()) {
                assets.open("open_jtalk_dic/$name").use { input ->
                    File(dictDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            marker.createNewFile()
            return dictDir
        }
    }

    // --- モデル一覧 ---

    /** 利用可能な全モデルの一覧(ダウンロード状態付き)。 */
    fun listModels(): List<VoicevoxModelInfo> =
        catalog.models.map { it.copy(isDownloaded = manager.isDownloaded(it.id)) }

    /** 全モデル共通の利用規約(VOICEVOX 音声モデル利用規約)のURL。 */
    val termsURL: String get() = catalog.termsURL

    // --- ライセンス同意 ---

    /** モデルの利用規約に同意する。アプリ側は規約を提示した上で呼ぶこと。 */
    fun acceptLicense(modelId: String) = gate.accept(modelId)

    /** 同意状態を確認する。 */
    fun isLicenseAccepted(modelId: String): Boolean = gate.isAccepted(modelId)

    // --- ダウンロード ---

    /** 1モデルをダウンロードする(要同意)。ダウンロード済みなら何もしない。 */
    suspend fun downloadModel(id: String) = manager.download(id)

    /**
     * 複数モデルを並列ダウンロードする(同時実行数は maxConcurrentDownloads)。
     * @return モデルIDごとの成否。一部失敗でも throw しない。
     */
    suspend fun downloadModels(ids: List<String>): Map<String, Result<Unit>> = coroutineScope {
        val semaphore = Semaphore(maxConcurrentDownloads)
        ids.map { id ->
            async {
                id to semaphore.withPermit { runCatching { manager.download(id) } }
            }
        }.awaitAll().toMap()
    }

    /** 全モデルを一括ダウンロードする(全モデルへの同意が必要)。 */
    suspend fun downloadAllModels(): Map<String, Result<Unit>> =
        downloadModels(catalog.models.map { it.id })

    // --- 合成 ---

    /**
     * テキストから音声(WAV)を合成する。
     *
     * モデルが未ダウンロードの場合は [VoicevoxException.ModelNotDownloaded] を投げる
     * (暗黙のダウンロードは行わない)。未ロードならロードしてから合成する。
     *
     * @param text 読み上げるテキスト。
     * @param modelId モデルID([listModels] の id)。
     * @param styleId スタイルID(characters[].styles[] から選ぶ)。null なら最初の talk スタイル。
     *   talk スタイルを持たないモデル(歌唱合成用の s0 など)は
     *   [VoicevoxException.TalkNotSupported] を投げる。
     * @param speedScale 話速 (既定 1.15)。
     */
    suspend fun synthesis(
        text: String,
        modelId: String,
        styleId: Int? = null,
        speedScale: Float = 1.15f,
    ): ByteArray {
        val info = manager.info(modelId)
        if (!info.supportsTalk) {
            throw VoicevoxException.TalkNotSupported(modelId)
        }
        if (!manager.isDownloaded(modelId)) {
            throw VoicevoxException.ModelNotDownloaded(modelId)
        }
        gate.require(modelId)

        loadMutex.withLock {
            if (modelId !in loadedModelIds) {
                withContext(Dispatchers.IO) {
                    VoiceModelFile(manager.localFile(modelId).absolutePath).use { model ->
                        synthesizer.loadVoiceModel(model)
                    }
                }
                loadedModelIds.add(modelId)
            }
        }

        val style = styleId
            ?: info.characters.asSequence()
                .flatMap { it.talkStyles }
                .firstOrNull()?.id
            ?: throw VoicevoxException.TalkNotSupported(modelId)
        // 合成はCPU負荷が高いのでDefaultディスパッチャで実行
        return withContext(Dispatchers.Default) {
            val audioQuery = synthesizer.createAudioQuery(text, style)
            audioQuery.speedScale = speedScale.toDouble()
            synthesizer.synthesis(audioQuery, style).perform()
        }
    }
}
