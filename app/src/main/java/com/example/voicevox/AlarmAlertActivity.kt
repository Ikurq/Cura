package com.example.voicevox // ※パッケージ名を確認してね！

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

    // 画面が表に出てきたときや、裏から戻ってきたときにチェックする魔法
    override fun onResume() {
        super.onResume()
        // もし通知欄から止められて、裏のサービスがもう消えていたら、この画面も自動で閉じるよ！
        if (!isServiceRunning(this, AlarmService::class.java)) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 寝落ち対策：画面を強制点灯させ、ロック画面の上にも表示する
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
        
        // 常に画面をオンに保つ
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm_alert)

        alertTimeText = findViewById(R.id.alertTime)
        updateCurrentTime()
        timeHandler.post(timeRunnable)

        val stopAlarmNowButton = findViewById<Button>(R.id.stopAlarmNowButton)
        val alarmId = intent.getStringExtra("ALARM_ID")

        stopAlarmNowButton.setOnClickListener {
            // 他の音を止める
            val serviceIntent = Intent(this, AlarmService::class.java)
            stopService(serviceIntent)
            
            // キャラクター経験値を付与
            addCharExp()
            
            // 繰り返し設定がある場合、次回のスケジュールを行う
            if (alarmId != null) {
                rescheduleNextAlarm(alarmId)
                // スケジュール読み上げを開始
                startMorningReading(alarmId)
            } else {
                finish()
            }
        }
    }

    private fun startMorningReading(alarmId: String) {
        val prefs = getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("alarmListJSON", null) ?: return
        val jsonArray = org.json.JSONArray(jsonString)
        
        var speakerId = 3 // デフォルト：ずんだもん
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.getString("id") == alarmId) {
                speakerId = obj.getInt("speakerId")
                break
            }
        }

        lifecycleScope.launch {
            val events = ScheduleLoader.loadAllEventsForToday(this@AlarmAlertActivity, Calendar.getInstance())
            val tasks = ScheduleLoader.loadTasksForToday(this@AlarmAlertActivity)

            if (events.isEmpty() && tasks.isEmpty()) {
                finish()
                return@launch
            }

            val now = Calendar.getInstance()
            val timeStrNow = SimpleDateFormat("H時m分", Locale.JAPAN).format(now.time)
            val sb = StringBuilder("おはようございます。ただいま、${timeStrNow}です。")

            if (events.isNotEmpty()) {
                sb.append("今日の予定は、")
                events.forEach { event ->
                    val time = SimpleDateFormat("H時m分", Locale.JAPAN).format(Date(event.startTime))
                    sb.append("${time}から${event.summary}、")
                }
                sb.append("です。")
            }
            if (tasks.isNotEmpty()) {
                sb.append("今日のタスクは、")
                tasks.forEach { sb.append("${it}、") }
                sb.append("があります。")
            }
            sb.append("今日も一日、元気に頑張りましょう！")

            val message = sb.toString()
            val outputFile = File(cacheDir, "morning_reading.wav")
            
            val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val apiKey = appPrefs.getString("custom_api_key", null)
            
            val client = WebVoicevoxClient()
            
            // UIを読み上げ中に更新（例：ボタンを無効化、テキストを変更）
            findViewById<Button>(R.id.stopAlarmNowButton).apply {
                isEnabled = false
                text = "読み上げ中..."
            }
            findViewById<TextView>(R.id.alertTime).text = "予定を確認中..."

            val success = withContext(Dispatchers.IO) {
                client.createAlarmAudio(message, speakerId, outputFile, apiKey)
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
                    .setUsage(AudioAttributes.USAGE_ALARM) // 目覚ましとして loud に再生
                    .build()
            )
            setDataSource(applicationContext, Uri.fromFile(file))
            setVolume(1.0f, 1.0f) // 最大音量に設定
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
        val prefs = getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("alarmListJSON", null) ?: return
        val jsonArray = org.json.JSONArray(jsonString)
        
        var targetItem: AlarmItem? = null
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.getString("id") == alarmId) {
                val repeatDays = ArrayList<Int>().apply {
                    val daysArr = obj.optJSONArray("repeatDays")
                    if (daysArr != null) {
                        for (j in 0 until daysArr.length()) add(daysArr.getInt(j))
                    }
                }
                targetItem = AlarmItem(
                    obj.getString("id"),
                    obj.getInt("hour"),
                    obj.getInt("minute"),
                    obj.getString("message"),
                    obj.getInt("speakerId"),
                    obj.getString("speakerName"),
                    obj.getBoolean("isEnabled"),
                    obj.getBoolean("readTasks"),
                    obj.optBoolean("vibrate", true),
                    repeatDays
                )
                break
            }
        }

        if (targetItem != null && targetItem.isEnabled) {
            if (targetItem.repeatDays.isNotEmpty()) {
                // 繰り返しありの場合は次を予約
                scheduleVoiceAlarm(this, targetItem)
            } else {
                // 繰り返しなしの場合
                if (targetItem.readTasks) {
                    // タスク読み上げありなら削除（その日限りとみなす）
                    deleteAlarm(alarmId)
                } else {
                    // 通常のアラームなら単にOFFにする
                    disableAlarm(alarmId)
                }
            }
        }
    }

    private fun deleteAlarm(alarmId: String) {
        val prefs = getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("alarmListJSON", null) ?: return
        val jsonArray = org.json.JSONArray(jsonString)
        val newList = org.json.JSONArray()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.getString("id") != alarmId) {
                newList.put(obj)
            }
        }
        prefs.edit().putString("alarmListJSON", newList.toString()).apply()
        
        // 音声ファイルも削除しておく（エコ設計）
        val audioFile = File(filesDir, "${alarmId}_alarm.wav")
        if (audioFile.exists()) audioFile.delete()
    }

    private fun disableAlarm(alarmId: String) {
        val prefs = getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("alarmListJSON", null) ?: return
        val jsonArray = org.json.JSONArray(jsonString)
        val newList = org.json.JSONArray()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            if (obj.getString("id") == alarmId) {
                obj.put("isEnabled", false)
            }
            newList.put(obj)
        }
        prefs.edit().putString("alarmListJSON", newList.toString()).apply()
    }

    private fun scheduleVoiceAlarm(context: Context, item: AlarmItem) =
        AlarmScheduler.schedule(context, item)

    // キャラクター経験値を加算するロジック（アラーム用）
    private fun addCharExp() {
        val prefs = getSharedPreferences("CharacterPrefs", Context.MODE_PRIVATE)
        val totalExp = prefs.getLong("totalExp", 0L)
        val now = System.currentTimeMillis()
        val lastRewardTime = prefs.getLong("last_alarm_reward_millis", 0L)

        // 0-12時(AM)か12-24時(PM)かの判定
        val calNow = Calendar.getInstance()
        val calLast = Calendar.getInstance().apply { timeInMillis = lastRewardTime }

        val isSameAmPm = calNow.get(Calendar.YEAR) == calLast.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calLast.get(Calendar.DAY_OF_YEAR) &&
                (calNow.get(Calendar.HOUR_OF_DAY) < 12) == (calLast.get(Calendar.HOUR_OF_DAY) < 12)

        if (isSameAmPm) {
            // すでにこの時間帯にレベルアップ済みなら少なめのEXP（あるいは0）
            val bonusExp = 10L
            prefs.edit().putLong("totalExp", totalExp + bonusExp).apply()
            Toast.makeText(this, "おはようございます！起床成功です！", Toast.LENGTH_SHORT).show()
            return
        }

        // 1レベル＝100 EXPの固定制
        fun getThreshold(lv: Int): Long {
            return (lv - 1) * 100L
        }

        var currentLv = 1
        while (totalExp >= currentLv * 100L) {
            currentLv++
        }

        // 次のレベルの閾値になるように調整（確定1レベル上昇）
        val newTotalExp = currentLv * 100L

        prefs.edit()
            .putLong("totalExp", newTotalExp)
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