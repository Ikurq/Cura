package com.example.voicevox

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

class TaskWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TaskRemoteViewsFactory(this.applicationContext)
    }
}

class TaskRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private val taskList = mutableListOf<TaskItem>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {}

    override fun onDataSetChanged() {
        taskList.clear()
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_TASK_LIST, null)
        if (jsonString != null) {
            try {
                // 完了済みのタスクはウィジェットに出さない
                val allTasks = json.decodeFromString<List<TaskItem>>(jsonString)
                taskList.addAll(allTasks.filter { !it.isCompleted })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // 優先度順、次に期限順にソート
        taskList.sortWith(compareByDescending<TaskItem> { it.getCurrentPriority() }.thenBy { it.deadlineMillis })
    }

    override fun onDestroy() {
        taskList.clear()
    }

    override fun getCount(): Int = taskList.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= taskList.size) return RemoteViews(context.packageName, R.layout.item_widget_task)

        val task = taskList[position]
        val views = RemoteViews(context.packageName, R.layout.item_widget_task)
        
        views.setTextViewText(R.id.widgetTaskTitle, task.title)
        
        // 期限の表示をよりカッコよく (例: [12:00] または [MM/dd])
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(task.deadlineMillis))
        views.setTextViewText(R.id.widgetTaskDeadline, "DEADLINE: $dateStr")
        
        // 優先度に応じた色の設定
        val priorityColor = when (task.getCurrentPriority()) {
            5 -> Color.parseColor("#FF007F") // サイバーピンク
            4 -> Color.parseColor("#00FFFF") // サイバーシアン
            3 -> Color.parseColor("#CCFF00") // ライム
            else -> Color.parseColor("#94A3B8") // グレー
        }
        views.setInt(R.id.widgetPriorityIndicator, "setBackgroundColor", priorityColor)

        // 行全体をタップした際にアプリを開く
        val fillInIntent = Intent()
        views.setOnClickFillInIntent(R.id.widgetTaskTitle, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
