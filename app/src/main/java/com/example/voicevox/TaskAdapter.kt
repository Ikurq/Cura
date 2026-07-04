package com.example.voicevox

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskAdapter(
    private val taskList: List<TaskItem>,
    private val onTaskStatusChanged: (TaskItem) -> Unit,
    private val onItemLongClicked: (TaskItem) -> Unit
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.taskCheckBox)
        val priorityBadge: TextView = view.findViewById(R.id.taskPriorityBadge)
        val titleText: TextView = view.findViewById(R.id.taskTitleText)
        val deadlineText: TextView = view.findViewById(R.id.taskDeadlineText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_card, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = taskList[position]
        val currentPriority = task.getCurrentPriority()

        // チェックボックスの状態
        holder.checkBox.setOnCheckedChangeListener(null) // リスナーの重複回避
        holder.checkBox.isChecked = task.isCompleted

        // 表示設定（完了済みなら薄く・取り消し線）
        if (task.isCompleted) {
            holder.titleText.paintFlags = holder.titleText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_secondary))
            holder.priorityBadge.visibility = View.INVISIBLE
            holder.deadlineText.visibility = View.GONE
        } else {
            holder.titleText.paintFlags = holder.titleText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.titleText.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_primary))
            holder.priorityBadge.visibility = View.VISIBLE
            holder.deadlineText.visibility = View.VISIBLE
        }

        holder.titleText.text = task.title
        
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        holder.deadlineText.text = "期限: ${sdf.format(Date(task.deadlineMillis))}"

        holder.priorityBadge.text = "P$currentPriority"
        
        val badgeColor = when (currentPriority) {
            5 -> R.color.priority_5
            4 -> R.color.priority_4
            3 -> R.color.priority_3
            2 -> R.color.priority_2
            else -> R.color.priority_1
        }
        holder.priorityBadge.background.setTint(ContextCompat.getColor(holder.itemView.context, badgeColor))

        // チェック操作
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            task.isCompleted = isChecked
            onTaskStatusChanged(task)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClicked(task)
            true
        }
    }

    override fun getItemCount(): Int = taskList.size
}
