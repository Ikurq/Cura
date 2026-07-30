package com.example.voicevox

import android.content.Context
import jp.voicevox.android.Voicevox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * VOICEVOX エンジンを制御する統合クライアント
 */
object CuraVoicevox {
    private var engine: Voicevox? = null
    private val synthesisJob = AtomicReference<Job?>(null)

    // キャッシュ判定用（メモリ）
    private var lastText: String? = null
    private var lastStyleId: String? = null
    private var lastSpeedScale: Float? = null

    /**
     * エンジンの初期化
     */
    suspend fun getEngine(context: Context): Voicevox = withContext(Dispatchers.IO) {
        val current = engine
        if (current != null) return@withContext current

        // 辞書データの準備
        val dictDir = File(context.filesDir, "voicevox/open_jtalk_dic")
        if (!File(dictDir, "char.bin").exists()) {
            dictDir.mkdirs()
            val assets = context.assets
            assets.list("open_jtalk_dic")?.forEach { fileName ->
                assets.open("open_jtalk_dic/$fileName").use { input ->
                    File(dictDir, fileName).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        val instance = Voicevox.create(context)
        engine = instance
        instance
    }

    /**
     * 音声合成を実行し、ファイルに保存する
     */
    suspend fun createAudio(
        context: Context,
        text: String,
        styleId: String,
        outputFile: File,
        speedScale: Float = 1.15f
    ): Boolean = withContext(Dispatchers.Default) {
        // 連打対策：実行中のJobがあればキャンセル
        val currentJob = Job()
        val oldJob = synthesisJob.getAndSet(currentJob)
        oldJob?.cancelAndJoin()

        try {
            // MD5キャッシュチェック（永続化）
            val cacheFile = getCacheFile(context, text, styleId, speedScale)
            val isTempFile = outputFile.absolutePath.contains("cache") || outputFile.absolutePath.contains("preview")

            if (isTempFile && cacheFile.exists() && cacheFile.length() > 0) {
                cacheFile.copyTo(outputFile, overwrite = true)
                return@withContext true
            }

            // メモリキャッシュチェック（高速化）
            if (isTempFile && outputFile.exists() &&
                text == lastText && styleId == lastStyleId && speedScale == lastSpeedScale) {
                return@withContext true
            }

            val vvx = getEngine(context)
            val models = vvx.listModels()
            val targetModelId = models.find { m -> 
                m.characters.any { c -> c.talkStyles.any { s -> s.id.toString() == styleId } }
            }?.id ?: return@withContext false

            if (!vvx.isLicenseAccepted(targetModelId)) return@withContext false

            val wavBytes = vvx.synthesis(text, targetModelId, styleId.toInt(), speedScale)
            outputFile.writeBytes(wavBytes)
            
            // 永続キャッシュに保存
            if (isTempFile) {
                cacheFile.parentFile?.mkdirs()
                outputFile.copyTo(cacheFile, overwrite = true)
                lastText = text
                lastStyleId = styleId
                lastSpeedScale = speedScale
            }
            
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                android.util.Log.d("CuraVoicevox", "Synthesis cancelled")
            } else {
                android.util.Log.e("CuraVoicevox", "Synthesis failed", e)
            }
            false
        } finally {
            synthesisJob.compareAndSet(currentJob, null)
        }
    }

    private fun getCacheFile(context: Context, text: String, styleId: String, speed: Float): File {
        val hash = md5("$text|$styleId|$speed")
        return File(File(context.filesDir, "voice_cache"), "cache_$hash.wav")
    }

    private fun md5(input: String): String {
        return MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    suspend fun getModels(context: Context) = getEngine(context).listModels()
    suspend fun isLicenseAccepted(context: Context, modelId: String) = getEngine(context).isLicenseAccepted(modelId)
    suspend fun acceptLicense(context: Context, modelId: String) = getEngine(context).acceptLicense(modelId)
}
