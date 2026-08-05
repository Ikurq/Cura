package com.example.voicevox

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ScheduleRemoteViewsFactory(this.applicationContext)
    }
}

class ScheduleRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private val scheduleList = mutableListOf<WidgetScheduleItem>()
    private val json = Json { ignoreUnknownKeys = true }

    data class WidgetScheduleItem(
        val time: String,
        val title: String,
        val typeLabel: String,
        val sortTime: Long,
        val location: String = ""
    )

    override fun onCreate() {}

    override fun onDataSetChanged() {
        scheduleList.clear()
        val today = Calendar.getInstance()
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())

        // 1. 今日の予定を取得 (ScheduleLoaderを使用)
        val events = ScheduleLoader.loadAllEventsForToday(context, today)
        events.forEach { event ->
            val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
            val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
            scheduleList.add(WidgetScheduleItem(
                sdfTime.format(Date(event.startTime)),
                event.summary,
                "[EVENT]",
                sortTime,
                event.location
            ))
        }

        // 2. 今日のタスクを取得
        val taskPrefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        taskPrefs.getString(CuraConstants.KEY_TASK_LIST, null)?.let { jsonStr ->
            try {
                val tasks = json.decodeFromString<List<TaskItem>>(jsonStr)
                tasks.filter { !it.isCompleted }.forEach { task ->
                    val cal = Calendar.getInstance().apply { timeInMillis = task.deadlineMillis }
                    if (isSameDay(cal, today)) {
                        val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
                        scheduleList.add(WidgetScheduleItem(
                            sdfTime.format(Date(task.deadlineMillis)),
                            task.title,
                            "[TASK]",
                            sortTime
                        ))
                    }
                }
            } catch (e: Exception) {}
        }

        scheduleList.sortBy { it.sortTime }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    override fun onDestroy() {
        scheduleList.clear()
    }

    override fun getCount(): Int = scheduleList.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= scheduleList.size) return RemoteViews(context.packageName, R.layout.item_widget_schedule)

        val item = scheduleList[position]
        val views = RemoteViews(context.packageName, R.layout.item_widget_schedule)
        
        views.setTextViewText(R.id.widgetScheduleTime, item.time)
        views.setTextViewText(R.id.widgetScheduleTitle, item.title)
        
        val infoText = if (item.location.isNotEmpty()) "${item.typeLabel} @ ${item.location}" else item.typeLabel
        views.setTextViewText(R.id.widgetScheduleInfo, infoText)

        // 行全体をタップした際にアプリを開く
        val fillInIntent = Intent()
        views.setOnClickFillInIntent(R.id.widgetScheduleTitle, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}
