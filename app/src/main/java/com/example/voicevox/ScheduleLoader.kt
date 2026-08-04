package com.example.voicevox

import android.content.Context
import kotlinx.serialization.json.Json
import java.util.*

object ScheduleLoader {

    private val json = Json { ignoreUnknownKeys = true }

    fun loadAllEventsForToday(context: Context, targetDate: Calendar): List<IcsEvent> {
        val allEvents = mutableListOf<IcsEvent>()

        // 1. Load Custom Events
        val schedulePrefs = context.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        schedulePrefs.getString(CuraConstants.KEY_EVENT_LIST, null)?.let { jsonStr ->
            try {
                val events = json.decodeFromString<List<IcsEvent>>(jsonStr)
                allEvents.addAll(events.filter { isSameDay(Calendar.getInstance().apply { timeInMillis = it.startTime }, targetDate) })
            } catch (e: Exception) {}
        }

        // 2. Load External ICS from Cache
        val timetablePrefs = context.getSharedPreferences(CuraConstants.PREFS_TIMETABLE, Context.MODE_PRIVATE)
        timetablePrefs.getString(CuraConstants.KEY_ICS_CACHE, null)?.let { jsonStr ->
            try {
                val events = json.decodeFromString<List<IcsEvent>>(jsonStr)
                allEvents.addAll(events.filter { isSameDay(Calendar.getInstance().apply { timeInMillis = it.startTime }, targetDate) })
            } catch (e: Exception) {}
        }

        // 3. Load Device Calendar Events
        val appPrefs = context.getSharedPreferences(CuraConstants.PREFS_APP, Context.MODE_PRIVATE)
        if (appPrefs.getBoolean("sync_device_calendar", false)) {
            allEvents.addAll(DeviceCalendarLoader.loadDeviceEvents(context, targetDate))
        }

        return allEvents.sortedBy { it.startTime }
    }

    fun loadTasksForToday(context: Context): List<String> {
        val today = Calendar.getInstance()
        return loadAllTasks(context)
            .filter { isSameDay(Calendar.getInstance().apply { timeInMillis = it.deadlineMillis }, today) }
            .map { it.title }
    }

    fun hasPriority5Tasks(context: Context): Boolean {
        return loadAllTasks(context).any { !it.isCompleted && it.getCurrentPriority() == 5 }
    }

    private fun loadAllTasks(context: Context): List<TaskItem> {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_TASK_LIST, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<TaskItem>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
