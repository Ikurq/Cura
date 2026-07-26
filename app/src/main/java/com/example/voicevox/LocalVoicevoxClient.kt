package com.example.voicevox

import android.content.Context
import jp.voicevox.android.Voicevox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Shakenokirimi12氏の vv-mobile エンジンを制御するクライアント
 */
class LocalVoicevoxClient(private val context: Context) {
    private var voicevox: Voicevox? = null
    private val synthesisJob = AtomicReference<Job?>(null)

    // キャッシュ管理用
    private var lastText: String? = null
    private var lastStyleId: String? = null
    private var lastSpeedScale: Float? = null

    /**
     * エンジンの初期化（初回のみ実行）
     */
    suspend fun getEngine(): Voicevox = withContext(Dispatchers.IO) {
        val current = voicevox
        if (current != null) return@withContext current

        // 【強化版】辞書データが正しく配置されているかチェックし、なければ強制コピー
        val dictDir = File(context.filesDir, "voicevox/open_jtalk_dic")
        val charBin = File(dictDir, "char.bin")
        
        if (!charBin.exists()) {
            android.util.Log.d("LocalVoicevoxClient", "Dictionary not found. Extracting manually...")
            dictDir.mkdirs()
            try {
                val assets = context.assets
                // assets/open_jtalk_dic 内のファイルをすべてコピー
                assets.list("open_jtalk_dic")?.forEach { fileName ->
                    assets.open("open_jtalk_dic/$fileName").use { input ->
                        File(dictDir, fileName).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                // ライブラリ側が「完了」と認識するためのマーカーを作成
                File(dictDir, ".complete").createNewFile()
                android.util.Log.d("LocalVoicevoxClient", "Dictionary extraction successful.")
            } catch (e: Exception) {
                android.util.Log.e("LocalVoicevoxClient", "Manual extraction failed", e)
            }
        }

        // ネイティブライブラリの初期化
        val instance = Voicevox.create(context)
        voicevox = instance
        instance
    }

    /**
     * 指定したモデルの規約に同意済みか確認する
     */
    suspend fun isLicenseAccepted(modelId: String): Boolean {
        return getEngine().isLicenseAccepted(modelId)
    }

    /**
     * 指定したモデルの規約に同意する
     */
    suspend fun acceptLicense(modelId: String) {
        getEngine().acceptLicense(modelId)
    }

    /**
     * 全モデル共通の利用規約URLを取得する
     * 変なファイルがダウンロードされないよう、公式サイトの規約ページに固定します
     */
    suspend fun getTermsUrl(): String {
        return "https://voicevox.hiroshiba.jp/term/"
    }

    /**
     * 指定したモデルIDのキャラクター固有規約URLを取得する
     * (もしエンジン側のURLが信頼できない場合は、ここも共通ページへ誘導するのが安全です)
     */
    suspend fun getCharacterTermsUrl(modelId: String): String? {
        val model = getEngine().listModels().find { it.id == modelId }
        val url = model?.characters?.firstOrNull()?.termsURL
        
        // .txt や .md で終わるURLは文字化けダウンロードの原因になるので除外
        if (url == null || url.endsWith(".txt") || url.endsWith(".md") || !url.startsWith("http")) {
            return null 
        }
        return url
    }

    /**
     * ローカルエンジンを使用して音声を合成する
     * @param text 喋らせる内容
     * @param styleId スタイルID (例: "3" は通常ずんだもんノーマル)
     * @param outputFile 出力先の .wav ファイル
     * @param speedScale 再生速度 (1.0が標準、数値が大きいほど速い)
     */
    suspend fun createAudio(
        text: String,
        styleId: String,
        outputFile: File,
        speedScale: Float = 1.15f // デフォルトを少し速めに設定
    ): Boolean = withContext(Dispatchers.Default) {
        // 連打対策：実行中のJobがあればキャンセルして待機する
        val currentJob = Job()
        val oldJob = synthesisJob.getAndSet(currentJob)
        oldJob?.cancelAndJoin()

        try {
            // キャッシュチェック（同じテキスト、同じスタイル、同じ速度なら再生成しない）
            // outputFile がキャッシュ用（preview.wav等）の場合のみ有効
            val isCacheable = outputFile.absolutePath.contains("cache") || outputFile.absolutePath.contains("preview")

            val vvx = getEngine()

            // スタイルIDから所属するモデルIDを特定する
            val models = vvx.listModels()
            var targetModelId: String? = null
            for (m in models) {
                if (m.characters.any { c -> c.talkStyles.any { s -> s.id.toString() == styleId } }) {
                    targetModelId = m.id
                    break
                }
            }

            if (targetModelId == null) {
                android.util.Log.e("LocalVoicevoxClient", "Style ID not found: $styleId")
                return@withContext false
            }

            // モデルがダウンロード済みか確認
            val modelInfo = models.find { it.id == targetModelId }
            
            // 同意されていない場合は合成を中断
            if (!vvx.isLicenseAccepted(targetModelId)) {
                android.util.Log.e("LocalVoicevoxClient", "License not accepted for model: $targetModelId")
                return@withContext false
            }

            if (modelInfo == null || !modelInfo.isDownloaded) {
                android.util.Log.d("LocalVoicevoxClient", "Downloading model: $targetModelId")
                vvx.downloadModel(targetModelId)
            }

            // 前回の生成内容と同じ、かつファイルが存在する場合はスキップ
            if (isCacheable && outputFile.exists() && 
                text == lastText && styleId == lastStyleId && speedScale == lastSpeedScale) {
                android.util.Log.d("LocalVoicevoxClient", "Same content detected. Skipping synthesis.")
                return@withContext true
            }

            val wavBytes = vvx.synthesis(text, targetModelId, styleId.toInt(), speedScale)

            outputFile.writeBytes(wavBytes)
            
            // キャッシュ情報を更新
            if (isCacheable) {
                lastText = text
                lastStyleId = styleId
                lastSpeedScale = speedScale
            }
            
            android.util.Log.d("LocalVoicevoxClient", "Success: Local synthesis finished with style $styleId.")
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                android.util.Log.d("LocalVoicevoxClient", "Synthesis cancelled for style $styleId")
            } else {
                android.util.Log.e("LocalVoicevoxClient", "Synthesis failed", e)
            }
            false
        } finally {
            // 自分が最新のJobならクリアする
            synthesisJob.compareAndSet(currentJob, null)
        }
    }

    /**
     * 利用可能なモデル（キャラクター）の一覧を取得する
     */
    suspend fun getModels() = withContext(Dispatchers.IO) {
        getEngine().listModels()
    }
}
