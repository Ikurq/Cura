package com.example.voicevox

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AlarmAlertActivity : AppCompatActivity() {

    private lateinit var alertTimeText: TextView
    private var mediaPlayer: MediaPlayer? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            timeHandler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isServiceRunning(this, AlarmService::class.java)) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm_alert)

        alertTimeText = findViewById(R.id.alertTime)
        updateCurrentTime()
        timeHandler.post(timeRunnable)

        val stopAlarmNowButton = findViewById<Button>(R.id.stopAlarmNowButton)
        val alarmId = intent.getStringExtra("ALARM_ID")

        stopAlarmNowButton.setOnClickListener {
            val serviceIntent = Intent(this, AlarmService::class.java)
            stopService(serviceIntent)
            
            // 起床統計とキャラクター経験値を付与
            updateWakeUpStatistics()
            addCharExp()
            
            if (alarmId != null) {
                rescheduleNextAlarm(alarmId)
                startMorningReading(alarmId)
            } else {
                finish()
            }
        }
    }

    private fun updateWakeUpStatistics() {
        val playerPrefs = getSharedPreferences(CuraConstants.PREFS_PLAYER, Context.MODE_PRIVATE)
        val currentCount = playerPrefs.getInt(CuraConstants.KEY_ALARM_WAKEUP_COUNT, 0)
        
        val nowStr = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date())
        val existingHistory = playerPrefs.getString(CuraConstants.KEY_WAKEUP_HISTORY, "") ?: ""
        val combined = ("> BOOT: $nowStr\n$existingHistory").split("\n").take(3).joinToString("\n")

        playerPrefs.edit {
            putInt(CuraConstants.KEY_ALARM_WAKEUP_COUNT, currentCount + 1)
            putBoolean(CuraConstants.KEY_PENDING_ALARM_PRAISE, true)
            putString(CuraConstants.KEY_WAKEUP_HISTORY, combined)
        }
    }

    private fun startMorningReading(alarmId: String) {
        val prefs = getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return
        
        val alarmList = try {
            Json.decodeFromString<List<AlarmItem>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
        
        val alarmItem = alarmList.find { it.id == alarmId } ?: return
        val styleId = alarmItem.speakerId

        lifecycleScope.launch {
            val events = ScheduleLoader.loadAllEventsForToday(this@AlarmAlertActivity, Calendar.getInstance())
            val tasks = ScheduleLoader.loadTasksForToday(this@AlarmAlertActivity)

            if (events.isEmpty() && tasks.isEmpty()) {
                finish()
                return@launch
            }

            val now = Calendar.getInstance()
            val timeStrNow = SimpleDateFormat("H時m分", Locale.JAPAN).format(now.time)
            
            val sb = StringBuilder()
            sb.append(String.format(Locale.getDefault(), AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "greeting"), timeStrNow))

            val mandatoryEvent = events.find { event ->
                val diffMinutes = (event.startTime - now.timeInMillis) / (1000 * 60)
                diffMinutes in -30..60
            }

            if (mandatoryEvent != null) {
                val diffMinutes = (mandatoryEvent.startTime - now.timeInMillis) / (1000 * 60)
                val templateKey = when {
                    diffMinutes > 0 -> "event_relative_before"
                    diffMinutes < 0 -> "event_relative_after"
                    else -> "event_relative_just"
                }
                val template = AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, templateKey)
                sb.append(String.format(Locale.getDefault(), template, mandatoryEvent.summary, if (diffMinutes < 0) -diffMinutes else diffMinutes))
            }

            if (events.isNotEmpty()) {
                sb.append(AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "event_list_header"))
                events.forEach { event ->
                    val time = SimpleDateFormat("H時m分", Locale.JAPAN).format(Date(event.startTime))
                    val template = AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "event_item")
                    sb.append(String.format(Locale.getDefault(), template, time, event.summary))
                }
                sb.append(AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "event_list_footer"))
            }
            
            if (tasks.isNotEmpty()) {
                sb.append(AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "task_list_header"))
                tasks.forEach { task ->
                    val template = AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "task_item")
                    sb.append(String.format(Locale.getDefault(), template, task))
                }
                sb.append(AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "task_list_footer"))
            }
            
            sb.append(AlarmTemplateManager.getMorningTemplate(this@AlarmAlertActivity, "closing"))

            val message = sb.toString()
            val outputFile = File(cacheDir, "morning_reading.wav")
            
            findViewById<Button>(R.id.stopAlarmNowButton).apply {
                isEnabled = false
                text = "読み上げ中..."
            }
            findViewById<TextView>(R.id.alertTime).text = "予定を確認中..."

            val success = withContext(Dispatchers.IO) {
                CuraVoicevox.createAudio(this@AlarmAlertActivity, message, styleId.toString(), outputFile)
            }

            if (success) {
                playAudio(outputFile)
            } else {
                finish()
            }
        }
    }

    private fun playAudio(file: File) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            )
            setDataSource(applicationContext, Uri.fromFile(file))
            setVolume(1.0f, 1.0f)
            setOnCompletionListener { 
                finish() 
            }
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun updateCurrentTime() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        alertTimeText.text = sdf.format(Date())
    }

    private fun rescheduleNextAlarm(alarmId: String) {
        val prefs = getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return
        
        val alarmList = try {
            Json.decodeFromString<List<AlarmItem>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
        
        val targetItem = alarmList.find { it.id == alarmId } ?: return

        if (targetItem.isEnabled) {
            if (targetItem.repeatDays.isNotEmpty()) {
                scheduleVoiceAlarm(this, targetItem)
            } else {
                if (targetItem.readTasks) {
                    deleteAlarm(alarmId)
                } else {
                    disableAlarm(alarmId)
                }
            }
        }
    }

    private fun deleteAlarm(alarmId: String) {
        val prefs = getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return
        
        val alarmList = try {
            Json.decodeFromString<List<AlarmItem>>(jsonString).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
        
        alarmList.removeAll { it.id == alarmId }
        prefs.edit().putString(CuraConstants.KEY_ALARM_LIST, Json.encodeToString<List<AlarmItem>>(alarmList)).apply()
        
        AlarmWidgetProvider.triggerUpdate(this)
        
        val audioFile = File(filesDir, "${alarmId}_alarm.wav")
        if (audioFile.exists()) audioFile.delete()
    }

    private fun disableAlarm(alarmId: String) {
        val prefs = getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return
        
        val alarmList = try {
            Json.decodeFromString<List<AlarmItem>>(jsonString).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
        
        alarmList.find { it.id == alarmId }?.let { it.isEnabled = false }
        prefs.edit().putString(CuraConstants.KEY_ALARM_LIST, Json.encodeToString<List<AlarmItem>>(alarmList)).apply()
        
        AlarmWidgetProvider.triggerUpdate(this)
    }

    private fun scheduleVoiceAlarm(context: Context, item: AlarmItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                android.util.Log.e("AlarmAlertActivity", "Cannot schedule exact alarm!")
            }
        }

        val audioPath = File(context.filesDir, "${item.id}_alarm.wav").absolutePath
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "ALARM_TRIGGER"
            putExtra("AUDIO_FILE_PATH", audioPath)
            putExtra("ALARM_ID", item.id)
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            item.id.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, item.hour)
            set(java.util.Calendar.MINUTE, item.minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        val now = System.currentTimeMillis()
        var minDiff = Long.MAX_VALUE
        var targetCalendar: java.util.Calendar? = null
        
        for (day in item.repeatDays) {
            val tempCal = calendar.clone() as java.util.Calendar
            tempCal.set(java.util.Calendar.DAY_OF_WEEK, day)
            if (tempCal.timeInMillis <= now) {
                tempCal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
            }
            val diff = tempCal.timeInMillis - now
            if (diff < minDiff) {
                minDiff = diff
                targetCalendar = tempCal
            }
        }
        targetCalendar?.let { calendar.timeInMillis = it.timeInMillis }

        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    private fun addCharExp() {
        val prefs = getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE)
        val totalExp = prefs.getLong(CuraConstants.KEY_TOTAL_EXP, 0L)
        val now = System.currentTimeMillis()
        val lastRewardTime = prefs.getLong("last_alarm_reward_millis", 0L)

        val calNow = Calendar.getInstance()
        val calLast = Calendar.getInstance().apply { timeInMillis = lastRewardTime }

        val isSameAmPm = calNow.get(Calendar.YEAR) == calLast.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calLast.get(Calendar.DAY_OF_YEAR) &&
                (calNow.get(Calendar.HOUR_OF_DAY) < 12) == (calLast.get(Calendar.HOUR_OF_DAY) < 12)

        if (isSameAmPm) {
            val bonusExp = 10L
            prefs.edit().putLong(CuraConstants.KEY_TOTAL_EXP, totalExp + bonusExp).apply()
            Toast.makeText(this, "おはようございます！起床成功です！", Toast.LENGTH_SHORT).show()
            return
        }

        val expPerLevel = CuraMessageManager.getIntConstant(this, "exp_per_level", 100).toLong()

        var currentLv = 1
        while (totalExp >= currentLv * expPerLevel) {
            currentLv++
        }

        val newTotalExp = currentLv * expPerLevel

        prefs.edit()
            .putLong(CuraConstants.KEY_TOTAL_EXP, newTotalExp)
            .putLong("last_alarm_reward_millis", now)
            .apply()

        Toast.makeText(this, "CHARACTER LEVEL UP! Lv.${currentLv + 1} になりました！", Toast.LENGTH_LONG).show()
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
