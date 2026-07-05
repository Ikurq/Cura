package com.example.voicevox

import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar

object DeviceCalendarLoader {

    data class DeviceCalendarInfo(val id: Long, val name: String, val account: String)

    fun getAllCalendars(context: Context): List<DeviceCalendarInfo> {
        val list = mutableListOf<DeviceCalendarInfo>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) 
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                list.add(DeviceCalendarInfo(
                    cursor.getLong(0),
                    cursor.getString(1) ?: "不明",
                    cursor.getString(2) ?: "不明"
                ))
            }
        }
        return list
    }

    fun loadDeviceEvents(context: Context, targetDate: Calendar): List<IcsEvent> {
        val events = mutableListOf<IcsEvent>()
        
        // ユーザーが選択したカレンダーIDを取得
        val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val selectedIdsJson = appPrefs.getString("selected_calendar_ids", null)
        val selectedIds = mutableSetOf<Long>()
        if (selectedIdsJson != null) {
            try {
                val arr = org.json.JSONArray(selectedIdsJson)
                for (i in 0 until arr.length()) selectedIds.add(arr.getLong(i))
            } catch (e: Exception) {}
        }
        
        // Ensure permission is granted (caller should check, but safe check here)
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) 
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val startMillis: Long = targetDate.clone().let {
            val c = it as Calendar
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.timeInMillis
        }
        val endMillis: Long = targetDate.clone().let {
            val c = it as Calendar
            c.set(Calendar.HOUR_OF_DAY, 23)
            c.set(Calendar.MINUTE, 59)
            c.set(Calendar.SECOND, 59)
            c.timeInMillis
        }

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.ACCOUNT_NAME,
            CalendarContract.Instances.CALENDAR_ID
        )

        val cursor = CalendarContract.Instances.query(
            context.contentResolver,
            projection,
            startMillis,
            endMillis
        )

        cursor?.use {
            while (it.moveToNext()) {
                val calId = it.getLong(6)
                // 選択されたカレンダーのみフィルタリング
                if (selectedIds.isNotEmpty() && !selectedIds.contains(calId)) continue

                val title = it.getString(0)
                val begin = it.getLong(1)
                val end = it.getLong(2)
                val location = it.getString(3) ?: ""
                val calName = it.getString(4) ?: "不明"
                val accountName = it.getString(5) ?: "不明"

                // ソース表示の重複を排除 (カレンダー名とアカウント名が同じなら片方だけ表示)
                val sourceLabel = if (calName == accountName) calName else "$calName / $accountName"

                // Create an IcsEvent representation (reusing existing model for consistency)
                events.add(IcsEvent(
                    summary = title, // タイトルからはソース元を削除し、スッキリさせる
                    startTime = begin,
                    endTime = end,
                    location = if (location.isNotEmpty()) "[$sourceLabel] $location" else "[$sourceLabel]"
                ))
            }
        }

        return events
    }
}
