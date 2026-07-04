package com.example.voicevox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class AlarmAdapter(
    private val alarmList: List<AlarmItem>,
    private val onSwitchChanged: (AlarmItem, Boolean) -> Unit,
    private val onItemLongClicked: (AlarmItem) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.AlarmViewHolder>() {

    class AlarmViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timeText: TextView = view.findViewById(R.id.cardTimeText)
        val speakerText: TextView = view.findViewById(R.id.cardSpeakerText)
        val repeatText: TextView = view.findViewById(R.id.cardRepeatText)
        val messageText: TextView = view.findViewById(R.id.cardMessageText)
        val alarmSwitch: MaterialSwitch = view.findViewById(R.id.cardAlarmSwitch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm_card, parent, false)
        return AlarmViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        val item = alarmList[position]

        // 時刻を「07:05」のような2桁形式で表示
        holder.timeText.text = String.format("%02d:%02d", item.hour, item.minute)
        holder.speakerText.text = item.speakerName
        
        // 繰り返しの曜日を表示
        if (item.repeatDays.isEmpty()) {
            holder.repeatText.text = "1回のみ"
        } else {
            val days = listOf("", "日", "月", "火", "水", "木", "金", "土")
            holder.repeatText.text = item.repeatDays.sorted().joinToString(" ") { days[it] }
        }

        holder.messageText.text = "「${item.message}」"

        // スイッチの状態を反映（リスナーを一時的に無効化して誤作動を防ぐ）
        holder.alarmSwitch.setOnCheckedChangeListener(null)
        holder.alarmSwitch.isChecked = item.isEnabled

        holder.alarmSwitch.setOnCheckedChangeListener { _, isChecked ->
            onSwitchChanged(item, isChecked)
        }

        // 長押しで削除できるようにイベントを設定
        holder.itemView.setOnLongClickListener {
            onItemLongClicked(item)
            true
        }
    }

    override fun getItemCount(): Int = alarmList.size
}