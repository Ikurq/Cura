package com.example.voicevox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

class WebVoicevoxClient {
    private val client = OkHttpClient()
    private val baseUrl = "https://deprecatedapis.tts.quest/v2/voicevox/audio/"

    /**
     * 音声を生成する。キャッシュがあればそれを利用する。
     */
    suspend fun createAlarmAudio(
        text: String,
        speakerId: Int,
        outputFile: File,
        apiKey: String? = null,
        useCache: Boolean = true,
        speed: Float = 1.0f
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // キャッシュ用のディレクトリ
            val cacheDir = File(outputFile.parentFile, "voice_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            // テキストと話者ID、速度から一意のファイル名（ハッシュ）を生成
            val hash = md5("$text|$speakerId|$speed")
            val cacheFile = File(cacheDir, "cache_$hash.wav")

            // キャッシュが有効で、ファイルが存在すればそれを利用
            if (useCache && cacheFile.exists() && cacheFile.length() > 0) {
                cacheFile.copyTo(outputFile, overwrite = true)
                return@withContext true
            }

            // 1. 音声合成リクエストを投げる
            val encodedText = URLEncoder.encode(text, "UTF-8")
            var url = "$baseUrl?text=$encodedText&speaker=$speakerId" // ENSURE SPEAKER ID IS HERE
            if (!apiKey.isNullOrEmpty()) {
                url += "&key=$apiKey"
            }
            if (speed != 1.0f) {
                url += "&speed=$speed"
            }
            
            android.util.Log.d("WebVoicevoxClient", "Requesting Audio from: $url")

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                android.util.Log.e("WebVoicevoxClient", "Request failed: ${response.code} ${response.message}\n$errorBody")
                return@withContext false
            }
            
            // 2. レスポンスの解析
            // su-shiki API v2 GET /audio/ returns the wav file directly if successful.
            val audioBytes = response.body?.bytes() ?: return@withContext false
            
            // キャッシュとして保存
            cacheFile.writeBytes(audioBytes)
            // 出力先へコピー
            cacheFile.copyTo(outputFile, overwrite = true)
            
            android.util.Log.d("WebVoicevoxClient", "Audio successfully downloaded and cached.")
            return@withContext true

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
