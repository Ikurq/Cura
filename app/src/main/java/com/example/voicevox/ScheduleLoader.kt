package com.example.voicevox

import android.content.Context
import org.json.JSONArray
import java.util.*

object ScheduleLoader {

    fun loadAllEventsForToday(context: Context, targetDate: Calendar): List<IcsEvent> {
        val allEvents = mutableListOf<IcsEvent>()

        // 1. Load Custom Events
        val schedulePrefs = context.getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val customJson = schedulePrefs.getString("eventListJSON", null)
        if (customJson != null) {
            try {
                val jsonArray = JSONArray(customJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                    if (isSameDay(cal, targetDate)) {
                        allEvents.add(IcsEvent(obj.getString("genre"), startTime, startTime, obj.getString("location")))
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. Load External ICS from Cache
        val timetablePrefs = context.getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)
        val icsJson = timetablePrefs.getString("icsCacheJSON", null)
        if (icsJson != null) {
            try {
                val jsonArray = JSONArray(icsJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                    if (isSameDay(cal, targetDate)) {
                        allEvents.add(IcsEvent(
                            obj.getString("summary"),
                            startTime,
                            obj.getLong("endTime"),
                            obj.getString("location")
                        ))
                    }
                }
            } catch (e: Exception) {}
        }

        return allEvents.sortedBy { it.startTime }
    }

    fun loadTasksForToday(context: Context): List<String> {
        val prefs = context.getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("taskListJSON", null)
        val list = mutableListOf<String>()
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                val today = Calendar.getInstance()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val deadlineMillis = obj.getLong("deadlineMillis")
                    val taskDate = Calendar.getInstance().apply { timeInMillis = deadlineMillis }
                    
                    if (isSameDay(taskDate, today)) {
                        list.add(obj.getString("title"))
                    }
                }
            } catch (e: Exception) {}
        }
        return list
    }

    fun hasPriority5Tasks(context: Context): Boolean {
        val prefs = context.getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("taskListJSON", null) ?: return false
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val isCompleted = obj.optBoolean("isCompleted", false)
                if (isCompleted) continue

                val id = obj.getString("id")
                val title = obj.getString("title")
                val deadlineMillis = obj.getLong("deadlineMillis")
                val basePriority = obj.getInt("basePriority")
                val linkedEventId = obj.optString("linkedEventId", null)

                val item = TaskItem(id, title, deadlineMillis, basePriority, linkedEventId, isCompleted)
                if (item.getCurrentPriority() == 5) return true
            }
        } catch (e: Exception) {}
        return false
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
