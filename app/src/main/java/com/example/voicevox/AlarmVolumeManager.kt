package com.example.voicevox

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * オーディオストリームの操作インターフェース（テスト容易化用）
 */
interface IAudioStreamAdapter {
    fun getMaxVolume(streamType: Int): Int
    fun getStreamVolume(streamType: Int): Int
    fun setStreamVolume(streamType: Int, index: Int, flags: Int)
}

/**
 * 実端末用のシステムオーディオストリームアダプタ
 */
class SystemAudioStreamAdapter(private val context: Context) : IAudioStreamAdapter {
    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun getMaxVolume(streamType: Int): Int {
        return audioManager?.getStreamMaxVolume(streamType) ?: 15
    }

    override fun getStreamVolume(streamType: Int): Int {
        return audioManager?.getStreamVolume(streamType) ?: 0
    }

    override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        audioManager?.setStreamVolume(streamType, index, flags)
    }
}

/**
 * アラーム鳴動時およびモーニング読み上げ時のシステムアラーム音量を安全に管理・ブーストするマネージャー
 */
object AlarmVolumeManager {
    private const val TAG = "AlarmVolumeManager"

    private fun logD(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // ユニットテスト環境等での android.util.Log スタブ例外を無視
        }
    }

    private fun logE(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        } catch (_: RuntimeException) {
            // ユニットテスト環境等での android.util.Log スタブ例外を無視
        }
    }

    // テスト注入用のアダプタプロバイダ
    var audioAdapterProvider: ((Context?) -> IAudioStreamAdapter)? = null

    internal var originalVolume: Int = -1
    internal var isBoosted: Boolean = false
    internal var activeHolders: Int = 0

    @Synchronized
    fun resetForTest() {
        originalVolume = -1
        isBoosted = false
        activeHolders = 0
        audioAdapterProvider = null
    }

    private fun getAdapter(context: Context?): IAudioStreamAdapter? {
        val provider = audioAdapterProvider
        if (provider != null) {
            return provider.invoke(context)
        }
        return context?.let { SystemAudioStreamAdapter(it) }
    }

    /**
     * アラーム音量を最大（または指定比率）にブーストする。
     * 複数画面・サービスからの多重取得に対応し、すべての利用が終了するまで音量を維持する。
     *
     * @param context コンテキスト
     * @param ratio 最大音量に対する目標比率（0.5f〜1.0f、デフォルト 1.0f = 100%）
     */
    @Synchronized
    fun acquireAlarmVolume(context: Context? = null, ratio: Float = 1.0f) {
        val adapter = getAdapter(context) ?: return
        activeHolders++

        if (isBoosted) {
            logD("Alarm volume already boosted (activeHolders: $activeHolders)")
            return
        }

        try {
            val maxVolume = adapter.getMaxVolume(AudioManager.STREAM_ALARM)
            val currentVolume = adapter.getStreamVolume(AudioManager.STREAM_ALARM)

            if (originalVolume == -1) {
                originalVolume = currentVolume
            }

            // 目標音量（最低でも最大音量の80%以上を確保）
            val targetVolume = (maxVolume * ratio.coerceIn(0.5f, 1.0f)).toInt()
                .coerceAtLeast((maxVolume * 0.8f).toInt().coerceAtLeast(1))
                .coerceAtMost(maxVolume)

            if (currentVolume < targetVolume) {
                adapter.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
                isBoosted = true
                logD("Boosted alarm stream volume: $currentVolume -> $targetVolume (max: $maxVolume)")
            } else {
                logD("Current alarm volume $currentVolume is already sufficient (target: $targetVolume)")
            }
        } catch (e: Exception) {
            logE("Failed to boost alarm volume", e)
        }
    }

    /**
     * アラーム音量の利用を解放し、すべての利用者が解放された時点で元の音量へ復元する。
     *
     * @param context コンテキスト
     * @param force true の場合は残存ホルダーにかかわらず強制復元
     */
    @Synchronized
    fun releaseAlarmVolume(context: Context? = null, force: Boolean = false) {
        val adapter = getAdapter(context) ?: return

        if (activeHolders > 0) {
            activeHolders--
        }

        if (force || activeHolders <= 0) {
            activeHolders = 0
            if (isBoosted && originalVolume != -1) {
                try {
                    adapter.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                    logD("Restored alarm stream volume to: $originalVolume")
                } catch (e: Exception) {
                    logE("Failed to restore alarm volume", e)
                }
            }
            originalVolume = -1
            isBoosted = false
        }
    }
}
