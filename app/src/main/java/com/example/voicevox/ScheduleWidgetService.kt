package com.example.voicevox

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class ScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ScheduleRemoteViewsFactory(this.applicationContext)
    }
}

class ScheduleRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private val scheduleList = mutableListOf<WidgetScheduleItem>()

    data class WidgetScheduleItem(
        val time: String,
        val title: String,
        val type: String,
        val sortTime: Long,
        val location: String = ""
    )

    override fun onCreate() {}

    override fun onDataSetChanged() {
        scheduleList.clear()
        val today = Calendar.getInstance()
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())

        // 1. Load Tasks for Today
        val taskPrefs = context.getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
        val taskJson = taskPrefs.getString("taskListJSON", null)
        if (taskJson != null) {
            try {
                val jsonArray = JSONArray(taskJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val deadline = obj.getLong("deadlineMillis")
                    val cal = Calendar.getInstance().apply { timeInMillis = deadline }
                    if (isSameDay(cal, today)) {
                        val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
                        scheduleList.add(WidgetScheduleItem(sdfTime.format(Date(deadline)), obj.getString("title"), "📝 タスク", sortTime))
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. Load Custom Events
        val schedulePrefs = context.getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val customJson = schedulePrefs.getString("eventListJSON", null)
        if (customJson != null) {
            try {
                val jsonArray = JSONArray(customJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                    if (isSameDay(cal, today)) {
                        val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
                        scheduleList.add(WidgetScheduleItem(sdfTime.format(Date(startTime)), obj.getString("genre"), "📌 予定", sortTime, obj.getString("location")))
                    }
                }
            } catch (e: Exception) {}
        }

        // 3. Load ICS Cache
        val timetablePrefs = context.getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)
        val icsJson = timetablePrefs.getString("icsCacheJSON", null)
        if (icsJson != null) {
            try {
                val jsonArray = JSONArray(icsJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                    if (isSameDay(cal, today)) {
                        val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
                        scheduleList.add(WidgetScheduleItem(sdfTime.format(Date(startTime)), obj.getString("summary"), "📅 外部予定", sortTime, obj.getString("location")))
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

    override fun onDestroy() { scheduleList.clear() }
    override fun getCount(): Int = scheduleList.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= scheduleList.size) return RemoteViews(context.packageName, R.layout.item_widget_schedule)

        val item = scheduleList[position]
        val views = RemoteViews(context.packageName, R.layout.item_widget_schedule)
        views.setTextViewText(R.id.widgetScheduleTime, item.time)
        views.setTextViewText(R.id.widgetScheduleTitle, item.title)
        
        val infoText = if (item.location.isNotEmpty()) "${item.type} @ ${item.location}" else item.type
        views.setTextViewText(R.id.widgetScheduleInfo, infoText)

        val fillInIntent = Intent()
        views.setOnClickFillInIntent(R.id.widgetScheduleTitle, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
}