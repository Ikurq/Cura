package com.example.voicevox

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class TaskWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TaskRemoteViewsFactory(this.applicationContext)
    }
}

class TaskRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private val taskList = mutableListOf<TaskItem>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        taskList.clear()
        val prefs = context.getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("taskListJSON", null)
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    taskList.add(
                        TaskItem(
                            obj.getString("id"),
                            obj.getString("title"),
                            obj.getLong("deadlineMillis"),
                            obj.getInt("basePriority")
                        )
                    )
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        taskList.sortWith(compareByDescending<TaskItem> { it.getCurrentPriority() }.thenBy { it.deadlineMillis })
    }

    override fun onDestroy() { taskList.clear() }
    override fun getCount(): Int = taskList.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= taskList.size) return RemoteViews(context.packageName, R.layout.item_widget_task)

        val task = taskList[position]
        val views = RemoteViews(context.packageName, R.layout.item_widget_task)
        views.setTextViewText(R.id.widgetTaskTitle, task.title)
        
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        views.setTextViewText(R.id.widgetTaskDeadline, sdf.format(Date(task.deadlineMillis)))
        
        val priorityColor = when (task.getCurrentPriority()) {
            5 -> 0xFFEF4444.toInt()
            4 -> 0xFFF59E0B.toInt()
            3 -> 0xFF3B82F6.toInt()
            2 -> 0xFF10B981.toInt()
            else -> 0xFF94A3B8.toInt()
        }
        views.setInt(R.id.widgetPriorityIndicator, "setColorFilter", priorityColor)

        val fillInIntent = Intent()
        views.setOnClickFillInIntent(R.id.widgetTaskTitle, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}