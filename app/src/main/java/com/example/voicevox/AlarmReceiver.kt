package com.example.voicevox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        android.util.Log.d("AlarmReceiver", "Received action: $action")
        
        try {
            if (action == "REFRESH_CALENDARS" || action == "SCHEDULE_NOTIFICATIONS") {
                if (action == "REFRESH_CALENDARS") {
                    refreshCalendars(context)
                    checkMandatoryReminder(context)
                }
                scheduleDailyNotifications(context)
                return
            }

            if (action == "SHOW_NOTIFICATION") {
                val title = intent.getStringExtra("TITLE") ?: "通知"
                val message = intent.getStringExtra("MESSAGE") ?: ""
                showSystemNotification(context, title, message)

                // 期限超過リマインドの場合、さらに1時間後を再予約する
                if (title.contains("【期限超過】")) {
                    rescheduleNextOverdueReminder(context, intent)
                }
                return
            }

            // Regular Alarm Logic
            val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            
            // 1. Vacation Mode Check
            if (appPrefs.getBoolean("vacation_mode", false)) {
                android.util.Log.d("AlarmReceiver", "Alarm skipped due to Vacation Mode")
                return
            }

            // 2. Holiday Check
            if (appPrefs.getBoolean("skip_holidays", false)) {
                if (HolidayHelper.isJapaneseHoliday(Calendar.getInstance())) {
                    android.util.Log.d("AlarmReceiver", "Alarm skipped due to Japanese Holiday")
                    return
                }
            }

            val audioFilePath = intent.getStringExtra("AUDIO_FILE_PATH")
            if (audioFilePath == null) {
                // If this is triggered without a file path, it's likely a malformed intent
                android.util.Log.e("AlarmReceiver", "Alarm triggered but Missing AUDIO_FILE_PATH. Action: $action")
                return
            }

            val alarmId = intent.getStringExtra("ALARM_ID")
            val vibrate = intent.getBooleanExtra("VIBRATE", true)

            // Start Service
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                putExtra("AUDIO_FILE_PATH", audioFilePath)
                putExtra("ALARM_ID", alarmId)
                putExtra("VIBRATE", vibrate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            // Note: AlarmService will launch AlarmAlertActivity via fullScreenIntent
            // for better compatibility with Android 10+ background restrictions.
            
        } catch (e: Exception) {
            android.util.Log.e("AlarmReceiver", "Error in onReceive: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun checkMandatoryReminder(context: Context) {
        val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("mandatory_reminder", true)) return

        val alarmPrefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString("alarmListJSON", "[]")
        
        // Today's mandatory alarm check
        val today = Calendar.getInstance()
        var hasMandatoryToday = false
        try {
            val jsonArray = JSONArray(alarmJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (!obj.optBoolean("isEnabled", true)) continue

                // Check repeatDays for today
                val repeatDays = obj.optJSONArray("repeatDays") // List of "Mon", "Tue", etc.
                val dayOfWeekStr = SimpleDateFormat("EEE", Locale.US).format(today.time) // "Mon", "Tue"...
                
                var scheduledForToday = false
                if (repeatDays == null || repeatDays.length() == 0) {
                    // One-time alarm. Check if it's for today.
                    // (Assuming one-time alarms set without specific date are for next occurrence)
                    scheduledForToday = true 
                } else {
                    for (j in 0 until repeatDays.length()) {
                        if (repeatDays.getString(j) == dayOfWeekStr) {
                            scheduledForToday = true
                            break
                        }
                    }
                }

                if (scheduledForToday && obj.getString("message").contains("本日の予定である")) {
                    hasMandatoryToday = true
                    break
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AlarmReceiver", "Error checking mandatory reminder: ${e.message}")
        }

        if (!hasMandatoryToday) {
            showSystemNotification(context, "絶対起きるアラーム未設定", "本日の重要予定に対するアラームが設定されていません。アプリを開いて設定を確認してください。")
        }
    }

    private fun scheduleDailyNotifications(context: Context) {
        val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val taskNotifyEnabled = appPrefs.getBoolean("task_notification", true)
        val eventNotifyEnabled = appPrefs.getBoolean("event_notification", true)
        
        android.util.Log.d("AlarmReceiver", "Scheduling notifications. Task: $taskNotifyEnabled, Event: $eventNotifyEnabled")

        if (!taskNotifyEnabled && !eventNotifyEnabled) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val today = Calendar.getInstance()
        val now = System.currentTimeMillis()

        // 1. Tasks
        if (taskNotifyEnabled) {
            val taskPrefs = context.getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
            val taskJson = taskPrefs.getString("taskListJSON", "[]")
            try {
                val jsonArray = JSONArray(taskJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optBoolean("isCompleted", false)) continue

                    val deadline = obj.getLong("deadlineMillis")
                    val title = obj.getString("title")

                    // A. Before deadline (1 hour before)
                    val notifyTime = deadline - (60 * 60 * 1000)
                    if (notifyTime > now && isSameDay(deadline, today)) {
                        android.util.Log.d("AlarmReceiver", "Scheduling Task Reminder: $title at $notifyTime")
                        scheduleSingleNotification(context, alarmManager, notifyTime, "タスク期限1時間前", title, i + 1000)
                    }

                    // B. After deadline (Hourly reminder)
                    if (now > deadline) {
                        // If deadline passed, schedule next hour from now
                        // We check this every time REFRESH_CALENDARS or SCHEDULE_NOTIFICATIONS is called (e.g. at boot or midnight or app open)
                        // To keep it simple, we schedule the "first" overdue reminder 1 hour from now.
                        val overdueTime = now + (60 * 60 * 1000)
                        android.util.Log.d("AlarmReceiver", "Scheduling Overdue Task Reminder: $title at $overdueTime")
                        scheduleSingleNotification(context, alarmManager, overdueTime, "【期限超過】タスク未完了", title, i + 5000)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. Events (Custom + ICS)
        if (eventNotifyEnabled) {
            // --- Custom Events ---
            val schedulePrefs = context.getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
            val customJson = schedulePrefs.getString("eventListJSON", "[]")
            try {
                val customArray = JSONArray(customJson)
                for (i in 0 until customArray.length()) {
                    val obj = customArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val notifyTime = startTime - (10 * 60 * 1000) // 10 mins before
                    if (notifyTime > System.currentTimeMillis() && isSameDay(startTime, today)) {
                        android.util.Log.d("AlarmReceiver", "Scheduling Custom Event: ${obj.getString("genre")} at $notifyTime")
                        scheduleSingleNotification(context, alarmManager, notifyTime, "予定10分前", obj.getString("genre"), i + 3000)
                    }
                }
            } catch (e: Exception) {}

            // --- ICS Events ---
            val timetablePrefs = context.getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)
            val icsJson = timetablePrefs.getString("icsCacheJSON", "[]")
            try {
                val jsonArray = JSONArray(icsJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val notifyTime = startTime - (10 * 60 * 1000) // 10 mins before
                    
                    if (notifyTime > System.currentTimeMillis() && isSameDay(startTime, today)) {
                        android.util.Log.d("AlarmReceiver", "Scheduling ICS Event: ${obj.getString("summary")} at $notifyTime")
                        scheduleSingleNotification(context, alarmManager, notifyTime, "予定10分前", obj.getString("summary"), i + 2000)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun rescheduleNextOverdueReminder(context: Context, oldIntent: Intent) {
        val title = oldIntent.getStringExtra("TITLE") ?: ""
        val message = oldIntent.getStringExtra("MESSAGE") ?: ""
        
        // 念のため、タスクがまだ未完了かチェック
        val taskPrefs = context.getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
        val taskJson = taskPrefs.getString("taskListJSON", "[]")
        var isStillPending = false
        try {
            val jsonArray = JSONArray(taskJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("title") == message && !obj.optBoolean("isCompleted", false)) {
                    isStillPending = true
                    break
                }
            }
        } catch (e: Exception) {}

        if (isStillPending) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val nextTime = System.currentTimeMillis() + (60 * 60 * 1000)
            // IDを維持するためにインテントから取得を試みる（またはメッセージのハッシュなど）
            val id = message.hashCode() + 5000 
            scheduleSingleNotification(context, alarmManager, nextTime, title, message, id)
            android.util.Log.d("AlarmReceiver", "Rescheduled overdue reminder for: $message")
        }
    }

    private fun isSameDay(millis: Long, today: Calendar): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
               cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    }

    private fun scheduleSingleNotification(context: Context, am: android.app.AlarmManager, time: Long, title: String, message: String, id: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "SHOW_NOTIFICATION"
            putExtra("TITLE", title)
            putExtra("MESSAGE", message)
        }
        val pi = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, time, pi)
    }

    private fun showSystemNotification(context: Context, title: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "reminder_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "リマインダー", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun refreshCalendars(context: Context) {
        val prefs = context.getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)
        val sourcesJson = prefs.getString("calendarSourcesJSON", null) ?: return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sourcesArray = JSONArray(sourcesJson)
                val allEvents = mutableListOf<IcsEvent>()
                
                for (i in 0 until sourcesArray.length()) {
                    val obj = sourcesArray.getJSONObject(i)
                    val urlStr = obj.getString("url")
                    try {
                        val url = URL(urlStr)
                        val connection = url.openConnection()
                        val lines = connection.getInputStream().use { stream ->
                            BufferedReader(InputStreamReader(stream)).readLines()
                        }
                        val events = IcsParser().parse(lines)
                        allEvents.addAll(events)
                    } catch (e: Exception) { e.printStackTrace() }
                }
                
                // Save Cache
                val cacheArray = JSONArray()
                for (event in allEvents) {
                    cacheArray.put(JSONObject().apply {
                        put("summary", event.summary)
                        put("startTime", event.startTime)
                        put("endTime", event.endTime)
                        put("location", event.location)
                    })
                }
                prefs.edit().putString("icsCacheJSON", cacheArray.toString()).apply()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}