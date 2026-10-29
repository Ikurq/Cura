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

    companion object {
        private const val DEFAULT_STYLE_ID = 3
    }

    private lateinit var alertTimeText: TextView
    private var mediaPlayer: MediaPlayer? = null
    private var isReading: Boolean = false
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            timeHandler.postDelayed(this, 1000)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!isReading && !isServiceRunning(this, AlarmService::class.java)) {
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
        sendBroadcast(Intent(CuraConstants.ACTION_ALARM_STARTED))

        alertTimeText = findViewById(R.id.alertTime)
        updateCurrentTime()
        timeHandler.post(timeRunnable)

        val stopAlarmNowButton = findViewById<Button>(R.id.stopAlarmNowButton)

        stopAlarmNowButton.setOnClickListener {
            if (isReading) return@setOnClickListener
            isReading = true

            stopAlarmNowButton.isEnabled = false
            stopAlarmNowButton.text = "予定を確認中..."
            alertTimeText.text = "アラーム停止"

            val serviceIntent = Intent(this, AlarmService::class.java)
            stopService(serviceIntent)
            
            // 起床統計とキャラクター経験値を付与
            updateWakeUpStatistics()
            addCharExp()

            val resolvedAlarmId = intent.getStringExtra("ALARM_ID") ?: AlarmService.currentAlarmId
            // アラーム削除・リスケジュール前に話者スタイルIDを特定
            val styleId = resolveSpeakerId(resolvedAlarmId)
            
            if (resolvedAlarmId != null) {
                rescheduleNextAlarm(resolvedAlarmId)
            }
            startMorningReading(styleId)
        }
    }

    private fun resolveSpeakerId(alarmId: String?): Int {
        if (alarmId == null) return DEFAULT_STYLE_ID
        val prefs = getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return DEFAULT_STYLE_ID
        return try {
            val alarmList = Json.decodeFromString<List<AlarmItem>>(jsonString)
            alarmList.find { it.id == alarmId }?.speakerId ?: DEFAULT_STYLE_ID
        } catch (e: Exception) {
            DEFAULT_STYLE_ID
        }
    }

    private fun updateWakeUpStatistics() {
        val playerPrefs = getSharedPreferences(CuraConstants.PREFS_PLAYER, Context.MODE_PRIVATE)
        val currentCount = playerPrefs.getInt(CuraConstants.KEY_ALARM_WAKEUP_COUNT, 0)
        
        playerPrefs.edit {
            putInt(CuraConstants.KEY_ALARM_WAKEUP_COUNT, currentCount + 1)
            putBoolean(CuraConstants.KEY_PENDING_ALARM_PRAISE, true)
        }
    }

    private fun startMorningReading(styleId: Int) {
        lifecycleScope.launch {
            try {
                val events = ScheduleLoader.loadAllEventsForToday(this@AlarmAlertActivity, Calendar.getInstance())
                val tasks = ScheduleLoader.loadTasksForToday(this@AlarmAlertActivity)

                val message = AlarmTemplateManager.buildMorningReadingMessage(
                    context = this@AlarmAlertActivity,
                    events = events,
                    tasks = tasks,
                    now = Calendar.getInstance()
                )

                val outputFile = File(cacheDir, "morning_reading.wav")
                
                findViewById<Button>(R.id.stopAlarmNowButton).apply {
                    isEnabled = false
                    text = "読み上げ中..."
                }
                findViewById<TextView>(R.id.alertTime).text = "予定を読み上げ中"

                val success = withContext(Dispatchers.IO) {
                    CuraVoicevox.createAudio(this@AlarmAlertActivity, message, styleId.toString(), outputFile)
                }

                if (success && outputFile.exists() && outputFile.length() > 0) {
                    playAudio(outputFile)
                } else {
                    finish()
                }
            } catch (e: Exception) {
                android.util.Log.e("AlarmAlertActivity", "Error during morning reading", e)
                finish()
            }
        }
    }

    private fun playAudio(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(applicationContext, Uri.fromFile(file))
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { 
                    finish() 
                }
                setOnErrorListener { _, _, _ ->
                    finish()
                    true
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            android.util.Log.e("AlarmAlertActivity", "Failed to play morning reading audio", e)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sendBroadcast(Intent(CuraConstants.ACTION_ALARM_DISMISSED))
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
                if (targetItem.isOneShot) {
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
        val now = System.currentTimeMillis()
        val lastRewardTime = prefs.getLong("last_alarm_reward_millis", 0L)
        val currentPending = prefs.getLong(CuraConstants.KEY_PENDING_EXP, 0L)

        val calNow = Calendar.getInstance()
        val calLast = Calendar.getInstance().apply { timeInMillis = lastRewardTime }

        val isSameAmPm = calNow.get(Calendar.YEAR) == calLast.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calLast.get(Calendar.DAY_OF_YEAR) &&
                (calNow.get(Calendar.HOUR_OF_DAY) < 12) == (calLast.get(Calendar.HOUR_OF_DAY) < 12)

        if (isSameAmPm) {
            val bonusExp = 10L
            prefs.edit().putLong(CuraConstants.KEY_PENDING_EXP, currentPending + bonusExp).apply()
            Toast.makeText(this, "おはようございます！起床成功です！", Toast.LENGTH_SHORT).show()
            return
        }

        val totalExp = prefs.getLong(CuraConstants.KEY_TOTAL_EXP, 0L)
        val expPerLevel = CuraMessageManager.getIntConstant(this, "exp_per_level", 100).toLong()

        var currentLv = 1
        while (totalExp >= currentLv * expPerLevel) {
            currentLv++
        }

        // 次のレベルまでの経験値をストックに
        val gain = expPerLevel 

        prefs.edit()
            .putLong(CuraConstants.KEY_PENDING_EXP, currentPending + gain)
            .putLong("last_alarm_reward_millis", now)
            .apply()

        Toast.makeText(this, "起床成功！ホーム画面で報酬を受け取ってください", Toast.LENGTH_LONG).show()
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
