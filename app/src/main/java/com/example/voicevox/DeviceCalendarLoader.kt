package com.example.voicevox

import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar

object DeviceCalendarLoader {

    /**
     * Represents a local Android Calendar retrieved from the device's CalendarProvider.
     */
    data class DeviceCalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val color: Int
    )

    /**
     * Queries all readable calendar sources from the device.
     */
    fun getAvailableCalendars(context: Context): List<DeviceCalendarInfo> {
        val list = mutableListOf<DeviceCalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR
        )

        val uri = CalendarContract.Calendars.CONTENT_URI
        val cursor = context.contentResolver.query(uri, projection, null, null, null)

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val displayName = it.getString(1) ?: "不明"
                val accountName = it.getString(2) ?: "不明"
                val color = it.getInt(3)
                list.add(DeviceCalendarInfo(id, displayName, accountName, color))
            }
        }

        return list
    }

    /**
     * Reads calendar instances for a specific day from the device.
     */
    fun loadDeviceEvents(context: Context, targetDate: Calendar): List<IcsEvent> {
        val events = mutableListOf<IcsEvent>()

        val prefs = context.getSharedPreferences(CuraConstants.PREFS_APP, Context.MODE_PRIVATE)
        val selectedIds = prefs.getStringSet("selected_device_calendar_ids", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

        val startMillis = (targetDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endMillis = (targetDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.ACCOUNT_NAME,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.ALL_DAY
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

                val title = it.getString(0) ?: "無題"
                val begin = it.getLong(1)
                val end = it.getLong(2)
                val location = it.getString(3) ?: ""
                val calName = it.getString(4) ?: "不明"
                val accountName = it.getString(5) ?: "不明"
                val isAllDay = it.getInt(7) == 1

                // ソース表示の重複を排除 (カレンダー名とアカウント名が同じなら片方だけ表示)
                val sourceLabel = if (calName == accountName) calName else "$calName / $accountName"

                // Create an IcsEvent representation (reusing existing model for consistency)
                events.add(IcsEvent(
                    summary = title,
                    startTime = begin,
                    endTime = end,
                    location = if (location.isNotEmpty()) "[$sourceLabel] $location" else "[$sourceLabel]",
                    isAllDay = isAllDay
                ))
            }
        }

        return events
    }
}
