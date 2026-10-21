package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AppUpdateHelper {

    private const val GITHUB_REPO = "Ikurq/Cura"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val RELEASES_WEB_URL = "https://github.com/$GITHUB_REPO/releases/latest"
    private const val FALLBACK_DOWNLOAD_URL = "https://github.com/$GITHUB_REPO/releases/latest/download/app-release.apk"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.1"
        } catch (e: Exception) {
            "2.1"
        }
    }

    fun checkAndPromptUpdate(context: Context, scope: CoroutineScope) {
        Toast.makeText(context, "最新バージョンを確認しています...", Toast.LENGTH_SHORT).show()

        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(RELEASES_API_URL)
                    .header("User-Agent", "Cura-Android-App")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                val bodyStr = response.body?.string() ?: throw Exception("Empty response")
                val json = JSONObject(bodyStr)
                val tagName = json.optString("tag_name", "最新版")
                val releaseNotes = json.optString("body", "").trim()
                val htmlUrl = json.optString("html_url", RELEASES_WEB_URL)

                var downloadUrl = FALLBACK_DOWNLOAD_URL
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", downloadUrl)
                            break
                        }
                    }
                }

                val currentVer = getCurrentVersion(context)

                withContext(Dispatchers.Main) {
                    showUpdateDialog(context, tagName, currentVer, releaseNotes, downloadUrl, htmlUrl)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showFallbackDialog(context)
                }
            }
        }
    }

    private fun showUpdateDialog(
        context: Context,
        latestTag: String,
        currentVer: String,
        releaseNotes: String,
        downloadUrl: String,
        htmlUrl: String
    ) {
        val notesDisplay = if (releaseNotes.isNotEmpty()) {
            val truncatedNotes = if (releaseNotes.length > 300) releaseNotes.take(300) + "..." else releaseNotes
            "\n\n【更新内容】\n$truncatedNotes"
        } else ""

        val message = "最新バージョン: $latestTag\n現在のバージョン: v$currentVer$notesDisplay"

        AlertDialog.Builder(context)
            .setTitle("最新バージョンの確認")
            .setMessage(message)
            .setPositiveButton("APKをダウンロード") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            .setNeutralButton("GitHubで確認") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showFallbackDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("最新バージョンの確認")
            .setMessage("GitHubからの最新バージョン情報取得に失敗しました。\nブラウザでReleasesページを開きますか？")
            .setPositiveButton("ブラウザで開く") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_WEB_URL)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
