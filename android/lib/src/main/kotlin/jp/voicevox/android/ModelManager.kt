package jp.voicevox.android

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** .vvm モデルファイルの実行時ダウンロードとローカルキャッシュ管理。 */
class ModelManager internal constructor(
    private val catalog: LicenseCatalog,
    private val gate: LicenseGate,
    private val modelsDir: File,
    private val client: OkHttpClient = OkHttpClient(),
) {
    init {
        modelsDir.mkdirs()
    }

    internal fun info(modelId: String): VoicevoxModelInfo =
        catalog.models.firstOrNull { it.id == modelId }
            ?: throw VoicevoxException.UnknownModel(modelId)

    /** モデルのローカルパス。 */
    fun localFile(modelId: String): File = File(modelsDir, info(modelId).filename)

    /** ダウンロード済みか。 */
    fun isDownloaded(modelId: String): Boolean = localFile(modelId).exists()

    /**
     * 1モデルをダウンロードする(要同意)。ダウンロード済みなら何もしない。
     */
    suspend fun download(modelId: String) {
        gate.require(modelId)
        val info = info(modelId)
        val dest = localFile(modelId)
        if (dest.exists()) return

        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(info.downloadURL).build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw VoicevoxException.DownloadFailed(modelId, "HTTP ${response.code}")
                    }
                    val body = response.body
                        ?: throw VoicevoxException.DownloadFailed(modelId, "empty body")
                    val tmp = File.createTempFile("vvm-", ".part", modelsDir)
                    try {
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        // 別コルーチンが先に完了していた場合は置き換えない
                        if (!dest.exists()) {
                            if (!tmp.renameTo(dest)) {
                                tmp.copyTo(dest, overwrite = false)
                            }
                        }
                    } finally {
                        tmp.delete()
                    }
                }
            } catch (e: VoicevoxException) {
                throw e
            } catch (e: Exception) {
                throw VoicevoxException.DownloadFailed(modelId, e.toString())
            }
        }
    }

    /** ダウンロード済みモデルを削除する。 */
    fun remove(modelId: String) {
        localFile(modelId).delete()
    }
}
