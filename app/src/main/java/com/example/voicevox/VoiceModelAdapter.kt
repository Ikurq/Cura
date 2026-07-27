package com.example.voicevox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import jp.voicevox.android.VoicevoxModelInfo

/**
 * 音声モデルの取得・削除を並べるリスト。
 *
 * モデルは1件あたり数十MB(全部で約1.7GB)あるので、必要なキャラクターだけ落として
 * 要らなくなったら消せるようにしておく。
 */
class VoiceModelAdapter(
    private val rows: List<Row>,
    private val onAction: (Row) -> Unit,
) : RecyclerView.Adapter<VoiceModelAdapter.ViewHolder>() {

    /** 1モデル分の表示状態。ダウンロード進捗は行ごとに持つ。 */
    class Row(val model: VoicevoxModelInfo) {
        var isDownloaded: Boolean = model.isDownloaded
        var downloadedBytes: Long = 0L
        var isDownloading: Boolean = false

        /** モデルに含まれるキャラクター名。1モデルに複数キャラが入っていることがある。 */
        val title: String = model.characters.joinToString("、") { it.name }

        val styleCount: Int = model.characters.sumOf { it.talkStyles.size }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.voiceModelName)
        val detail: TextView = view.findViewById(R.id.voiceModelDetail)
        val action: Button = view.findViewById(R.id.btnVoiceModelAction)
        val progress: ProgressBar = view.findViewById(R.id.voiceModelProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_voice_model, parent, false)
        )

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.name.text = row.title

        val sizeMb = row.model.sizeBytes / (1024.0 * 1024.0)
        holder.detail.text = when {
            row.isDownloading -> String.format(
                Locale.getDefault(),
                "取得中… %.1f / %.1f MB",
                row.downloadedBytes / (1024.0 * 1024.0),
                sizeMb,
            )
            row.isDownloaded -> String.format(
                Locale.getDefault(), "取得済み ・ %.1f MB ・ %d スタイル", sizeMb, row.styleCount
            )
            else -> String.format(
                Locale.getDefault(), "未取得 ・ %.1f MB ・ %d スタイル", sizeMb, row.styleCount
            )
        }

        holder.progress.visibility = if (row.isDownloading) View.VISIBLE else View.GONE
        if (row.isDownloading && row.model.sizeBytes > 0) {
            holder.progress.progress =
                ((row.downloadedBytes * 100) / row.model.sizeBytes).toInt().coerceIn(0, 100)
        }

        holder.action.isEnabled = !row.isDownloading
        holder.action.text = when {
            row.isDownloading -> "取得中"
            row.isDownloaded -> "削除"
            else -> "取得"
        }
        holder.action.setOnClickListener { onAction(row) }
    }
}
