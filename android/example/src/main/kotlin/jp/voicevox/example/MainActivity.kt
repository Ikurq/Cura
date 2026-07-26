package jp.voicevox.example

import android.app.AlertDialog
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import jp.voicevox.android.Voicevox
import jp.voicevox.android.VoicevoxModelInfo
import kotlinx.coroutines.launch

/**
 * voicevox-core-android のサンプルアプリ。
 * モデル一覧 → 利用規約同意 → ダウンロード → 合成 → 再生の一連を実演する。
 * UIはシンプルさ優先でコードレイアウト(ビルド依存を最小化)。
 */
class MainActivity : AppCompatActivity() {
    private var voicevox: Voicevox? = null
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var textInput: EditText
    private lateinit var modelList: LinearLayout
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        lifecycleScope.launch {
            try {
                val vv = Voicevox.create(this@MainActivity)
                voicevox = vv
                renderModels(vv.listModels())
                setStatus("準備完了 (${vv.listModels().size} モデル)", busy = false)

                // 開発検証用の自動E2E(adb shell am start --ez auto_e2e true で有効化)
                if (intent.getBooleanExtra("auto_e2e", false)) {
                    runAutoE2E(vv)
                }
            } catch (e: Exception) {
                setStatus("初期化失敗: $e", busy = false)
            }
        }
    }

    private suspend fun runAutoE2E(vv: Voicevox) {
        setStatus("E2E: 未同意DLの拒否を確認中...", busy = true)
        try {
            vv.downloadModel("1")
            setStatus("E2E失敗: 未同意DLが通ってしまった", busy = false)
            return
        } catch (_: Exception) {
            // 期待どおり拒否された
        }
        try {
            setStatus("E2E: モデル0をダウンロード中...", busy = true)
            vv.acceptLicense("0")
            vv.downloadModel("0")
            renderModels(vv.listModels())
            setStatus("E2E: 合成中...", busy = true)
            // ずんだもん(スタイル3=ノーマル)
            val wav = vv.synthesis("こんにちは、ずんだもんなのだ", modelId = "0", styleId = 3)
            val header = wav.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            setStatus("E2E成功: ${wav.size} bytes, header=\"$header\"", busy = false)
        } catch (e: Exception) {
            setStatus("E2E失敗: $e", busy = false)
        }
    }

    private fun download(model: VoicevoxModelInfo) {
        val vv = voicevox ?: return
        val characters = model.characters.joinToString("、") { it.name }
        AlertDialog.Builder(this)
            .setTitle("利用規約への同意")
            .setMessage(
                "このモデルには $characters が含まれます。\n\n" +
                    "利用には VOICEVOX 音声モデル利用規約および各キャラクターの規約への同意が必要です。" +
                    "生成音声の利用時はクレジット表記(例: ${model.characters.first().creditText})が必要です。\n\n" +
                    "規約: ${vv.termsURL}"
            )
            .setNegativeButton("同意しない", null)
            .setPositiveButton("同意する") { _, _ ->
                lifecycleScope.launch {
                    setStatus("${model.id} をダウンロード中...", busy = true)
                    try {
                        vv.acceptLicense(model.id)
                        vv.downloadModel(model.id)
                        renderModels(vv.listModels())
                        setStatus("${model.id} ダウンロード完了", busy = false)
                    } catch (e: Exception) {
                        setStatus("$e", busy = false)
                    }
                }
            }
            .show()
    }

    /** キャラクター×スタイル(talkのみ)を選ばせてから合成する。
     *  モデルによって10以上のスタイルがあるため、必ずスクロール可能な
     *  ListView(AlertDialog.Builder#setSingleChoiceItems)で表示する。
     *  (setItems の M3 レンダリングでは項目が3つに見切れる問題があった) */
    private fun selectStyleAndSynthesize(model: VoicevoxModelInfo) {
        val styles = model.characters.flatMap { c ->
            c.talkStyles.map { s -> "${c.name}(${s.name})" to s.id }
        }
        if (styles.isEmpty()) {
            setStatus("このモデルは歌唱合成用のため読み上げには使えません", busy = false)
            return
        }
        val labels = styles.map { it.first }.toTypedArray()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("キャラクター・スタイルを選択(${styles.size}件)")
            .setSingleChoiceItems(labels, -1) { d, which ->
                d.dismiss()
                synthesize(model, styles[which].second)
            }
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.show()
        // 縦の可視スタイル数を制限してリストを必ずスクロール可能に見せる
        dialog.listView?.let { lv ->
            val density = resources.displayMetrics.density
            lv.layoutParams = lv.layoutParams.apply {
                height = (48 * density * 8).toInt() // 約8件で高さを固定 → 残りはスクロール
            }
            lv.requestLayout()
        }
    }

    private fun synthesize(model: VoicevoxModelInfo, styleId: Int) {
        val vv = voicevox ?: return
        lifecycleScope.launch {
            setStatus("合成中...", busy = true)
            try {
                val wav = vv.synthesis(textInput.text.toString(), modelId = model.id, styleId = styleId)
                val file = File(cacheDir, "voicevox_out.wav").apply { writeBytes(wav) }
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
                setStatus("再生中 (styleId=$styleId, ${wav.size} bytes)", busy = false)
            } catch (e: Exception) {
                setStatus("$e", busy = false)
            }
        }
    }

    // --- UI ---

    private fun buildLayout(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        textInput = EditText(this).apply { setText("こんにちは、ずんだもんなのだ") }
        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            visibility = android.view.View.GONE
        }
        statusView = TextView(this).apply { text = "初期化中..." }
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progress)
            addView(statusView)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = "voicevox-core-android example"
                textSize = 18f
            })
            addView(textInput)
            addView(statusRow)
            addView(modelList)
        }
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderModels(models: List<VoicevoxModelInfo>) {
        modelList.removeAllViews()
        for (model in models) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${model.id}: ${model.characters.joinToString("、") { it.name }}"
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = if (model.isDownloaded) "再生" else "DL"
                setOnClickListener {
                    if (model.isDownloaded) selectStyleAndSynthesize(model) else download(model)
                }
            })
            modelList.addView(row)
        }
    }

    private fun setStatus(message: String, busy: Boolean) {
        statusView.text = message
        progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }
}
