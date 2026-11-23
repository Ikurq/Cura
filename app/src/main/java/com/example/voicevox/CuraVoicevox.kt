package com.example.voicevox

import android.content.Context
import jp.voicevox.android.Voicevox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * VOICEVOX エンジンを制御する統合クライアント
 */
object CuraVoicevox {
    @Volatile
    private var engine: Voicevox? = null
    private val engineMutex = Mutex()
    private val synthesisJob = AtomicReference<Job?>(null)

    // キャッシュ判定用（メモリ）
    private var lastText: String? = null
    private var lastStyleId: String? = null
    private var lastSpeedScale: Float? = null
    private var lastVolumeScale: Float? = null

    /**
     * エンジンの初期化（スレッドセーフ）
     */
    suspend fun getEngine(context: Context): Voicevox = withContext(Dispatchers.IO) {
        engine?.let { return@withContext it }
        engineMutex.withLock {
            engine?.let { return@withLock it }
            val instance = Voicevox.create(context.applicationContext)
            engine = instance
            instance
        }
    }

    /**
     * 音声合成を実行し、ファイルに保存する
     */
    suspend fun createAudio(
        context: Context,
        text: String,
        styleId: String,
        outputFile: File,
        speedScale: Float = 1.15f,
        volumeScale: Float = 1.6f
    ): Boolean = withContext(Dispatchers.Default) {
        // 連打対策：実行中のJobがあればキャンセル
        val currentJob = Job()
        val oldJob = synthesisJob.getAndSet(currentJob)
        oldJob?.cancelAndJoin()

        try {
            // MD5キャッシュチェック（永続化）
            val cacheFile = getCacheFile(context, text, styleId, speedScale, volumeScale)
            val isTempFile = outputFile.absolutePath.contains("cache") || outputFile.absolutePath.contains("preview")

            if (isTempFile && cacheFile.exists() && cacheFile.length() > 0) {
                cacheFile.copyTo(outputFile, overwrite = true)
                return@withContext true
            }

            // メモリキャッシュチェック（高速化）
            if (isTempFile && outputFile.exists() &&
                text == lastText && styleId == lastStyleId && speedScale == lastSpeedScale && volumeScale == lastVolumeScale) {
                return@withContext true
            }

            val vvx = getEngine(context)
            val models = vvx.listModels()
            val targetModel = models.find { m ->
                m.characters.any { c -> c.talkStyles.any { s -> s.id.toString() == styleId } }
            } ?: return@withContext false

            if (!vvx.isLicenseAccepted(targetModel.id)) return@withContext false

            // モデルがローカルに未ダウンロードの場合は事前にダウンロードを実行
            if (!targetModel.isDownloaded) {
                vvx.downloadModel(targetModel.id)
            }

            val wavBytes = vvx.synthesis(text, targetModel.id, styleId.toInt(), speedScale, volumeScale)
            outputFile.writeBytes(wavBytes)
            
            // 永続キャッシュに保存
            if (isTempFile) {
                cacheFile.parentFile?.mkdirs()
                outputFile.copyTo(cacheFile, overwrite = true)
                lastText = text
                lastStyleId = styleId
                lastSpeedScale = speedScale
                lastVolumeScale = volumeScale
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

    internal fun getCacheKey(text: String, styleId: String, speed: Float, volume: Float): String {
        return "$text|$styleId|$speed|$volume"
    }

    internal fun getCacheFile(context: Context, text: String, styleId: String, speed: Float, volume: Float = 1.6f): File {
        val hash = md5(getCacheKey(text, styleId, speed, volume))
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
