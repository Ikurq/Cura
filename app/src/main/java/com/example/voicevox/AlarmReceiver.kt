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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class AlarmReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

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

                if (title.contains("【期限超過】")) {
                    rescheduleNextOverdueReminder(context, intent)
                }
                return
            }

            // Regular Alarm Logic
            val audioFilePath = intent.getStringExtra("AUDIO_FILE_PATH")
            val alarmId = intent.getStringExtra("ALARM_ID")
            val vibrate = intent.getBooleanExtra("VIBRATE", true)

            if (audioFilePath != null) {
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
                return
            }
            
        } catch (e: Exception) {
            android.util.Log.e("AlarmReceiver", "Error in onReceive: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun checkMandatoryReminder(context: Context) {
        val appPrefs = context.getSharedPreferences(CuraConstants.PREFS_APP, Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("mandatory_reminder", true)) return

        val alarmPrefs = context.getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString(CuraConstants.KEY_ALARM_LIST, "[]")
        
        val today = Calendar.getInstance()
        var hasMandatoryToday = false
        try {
            val alarmList = json.decodeFromString<List<AlarmItem>>(alarmJson ?: "[]")
            
            for (item in alarmList) {
                if (!item.isEnabled) continue

                var scheduledForToday = false
                if (item.repeatDays.isEmpty()) {
                    scheduledForToday = true 
                } else {
                    // repeatDays is List<Int> (1=Sun, 2=Mon...)
                    val todayInt = today.get(Calendar.DAY_OF_WEEK)
                    if (item.repeatDays.contains(todayInt)) {
                        scheduledForToday = true
                    }
                }

                if (scheduledForToday && item.message.contains("本日の予定である")) {
                    hasMandatoryToday = true
                    break
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AlarmReceiver", "Error checking mandatory reminder: ${e.message}")
        }

        if (!hasMandatoryToday) {
            showSystemNotification(context, "予定連動アラーム未設定", "本日の予定に対するアラームが設定されていません。アプリを確認してください。")
        }
    }

    private fun scheduleDailyNotifications(context: Context) {
        val appPrefs = context.getSharedPreferences(CuraConstants.PREFS_APP, Context.MODE_PRIVATE)
        val taskNotifyEnabled = appPrefs.getBoolean("task_notification", true)
        val eventNotifyEnabled = appPrefs.getBoolean("event_notification", true)
        
        if (!taskNotifyEnabled && !eventNotifyEnabled) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val today = Calendar.getInstance()
        val now = System.currentTimeMillis()

        if (taskNotifyEnabled) {
            val taskPrefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
            val taskJson = taskPrefs.getString(CuraConstants.KEY_TASK_LIST, "[]")
            try {
                val tasks = json.decodeFromString<List<TaskItem>>(taskJson ?: "[]")
                tasks.filter { !it.isCompleted }.forEachIndexed { i, task ->
                    val deadline = task.deadlineMillis
                    val notifyTime = deadline - (60 * 60 * 1000)
                    if (notifyTime > now && isSameDay(deadline, today)) {
                        scheduleSingleNotification(context, alarmManager, notifyTime, "タスク期限1時間前", task.title, i + 1000)
                    }
                    if (now > deadline) {
                        val overdueTime = now + (60 * 60 * 1000)
                        scheduleSingleNotification(context, alarmManager, overdueTime, "【期限超過】タスク未完了", task.title, i + 5000)
                    }
                }
            } catch (e: Exception) {}
        }

        if (eventNotifyEnabled) {
            val schedulePrefs = context.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
            val customJson = schedulePrefs.getString(CuraConstants.KEY_EVENT_LIST, "[]")
            try {
                val events = json.decodeFromString<List<ScheduleEvent>>(customJson ?: "[]")
                events.forEachIndexed { i, event ->
                    val notifyTime = event.startTime - (10 * 60 * 1000)
                    if (notifyTime > System.currentTimeMillis() && isSameDay(event.startTime, today)) {
                        scheduleSingleNotification(context, alarmManager, notifyTime, "予定10分前", event.summary, i + 3000)
                    }
                }
            } catch (e: Exception) {}

            val timetablePrefs = context.getSharedPreferences(CuraConstants.PREFS_TIMETABLE, Context.MODE_PRIVATE)
            val icsJson = timetablePrefs.getString(CuraConstants.KEY_ICS_CACHE, "[]")
            try {
                val events = json.decodeFromString<List<IcsEvent>>(icsJson ?: "[]")
                events.forEachIndexed { i, event ->
                    val notifyTime = event.startTime - (10 * 60 * 1000)
                    if (notifyTime > System.currentTimeMillis() && isSameDay(event.startTime, today)) {
                        scheduleSingleNotification(context, alarmManager, notifyTime, "予定10分前", event.summary, i + 2000)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun rescheduleNextOverdueReminder(context: Context, oldIntent: Intent) {
        val title = oldIntent.getStringExtra("TITLE") ?: ""
        val message = oldIntent.getStringExtra("MESSAGE") ?: ""
        
        val taskPrefs = context.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        val taskJson = taskPrefs.getString(CuraConstants.KEY_TASK_LIST, "[]")
        var isStillPending = false
        try {
            val tasks = json.decodeFromString<List<TaskItem>>(taskJson ?: "[]")
            isStillPending = tasks.any { it.title == message && !it.isCompleted }
        } catch (e: Exception) {}

        if (isStillPending) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val nextTime = System.currentTimeMillis() + (60 * 60 * 1000)
            val id = message.hashCode() + 5000 
            scheduleSingleNotification(context, alarmManager, nextTime, title, message, id)
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
            val channel = NotificationChannel(channelId, "リマインダー", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_cura)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun refreshCalendars(context: Context) {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_TIMETABLE, Context.MODE_PRIVATE)
        val sourcesJson = prefs.getString("calendarSourcesJSON", null) ?: return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sourcesArray = org.json.JSONArray(sourcesJson)
                val allEvents = mutableListOf<IcsEvent>()
                
                for (i in 0 until sourcesArray.length()) {
                    val obj = sourcesArray.getJSONObject(i)
                    val urlStr = obj.getString("url")
                    try {
                        val url = URL(urlStr)
                        val lines = url.openConnection().getInputStream().use { stream ->
                            BufferedReader(InputStreamReader(stream)).readLines()
                        }
                        allEvents.addAll(IcsParser().parse(lines))
                    } catch (e: Exception) { e.printStackTrace() }
                }
                
                prefs.edit().putString(CuraConstants.KEY_ICS_CACHE, Json.encodeToString(allEvents)).apply()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
