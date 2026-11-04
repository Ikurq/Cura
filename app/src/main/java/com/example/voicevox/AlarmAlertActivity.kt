package com.example.voicevox

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AlarmAlertActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_STYLE_ID = 3
        private const val REQUEST_CAMERA_CODE = 1001
    }

    private lateinit var alertTimeText: TextView
    private lateinit var stopAlarmNowButton: Button
    private lateinit var alertQrBadge: TextView
    private lateinit var qrScannerOverlay: LinearLayout
    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private lateinit var qrPromptText: TextView
    private lateinit var btnCancelQrScan: Button

    private var mediaPlayer: MediaPlayer? = null
    private var isReading: Boolean = false
    private var isScanning: Boolean = false
    private var currentAlarmItem: AlarmItem? = null

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
        if (isScanning) {
            barcodeScannerView.resume()
        }
        if (!isReading && !isServiceRunning(this, AlarmService::class.java)) {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) {
            barcodeScannerView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
        if (isScanning) {
            barcodeScannerView.pause()
        }
        mediaPlayer?.release()
        mediaPlayer = null
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
        stopAlarmNowButton = findViewById(R.id.stopAlarmNowButton)
        alertQrBadge = findViewById(R.id.alertQrBadge)
        qrScannerOverlay = findViewById(R.id.qrScannerOverlay)
        barcodeScannerView = findViewById(R.id.barcodeScannerView)
        qrPromptText = findViewById(R.id.qrPromptText)
        btnCancelQrScan = findViewById(R.id.btnCancelQrScan)

        updateCurrentTime()
        timeHandler.post(timeRunnable)

        val resolvedAlarmId = intent.getStringExtra("ALARM_ID") ?: AlarmService.currentAlarmId
        currentAlarmItem = resolveAlarmItem(resolvedAlarmId)

        val isQrRequired = currentAlarmItem?.requireQrCode == true
        if (isQrRequired) {
            alertQrBadge.visibility = View.VISIBLE
            stopAlarmNowButton.text = getString(R.string.alarm_scan_qr_button)
        } else {
            alertQrBadge.visibility = View.GONE
            stopAlarmNowButton.text = "アラームを停止"
        }

        stopAlarmNowButton.setOnClickListener {
            if (isReading) return@setOnClickListener

            if (isQrRequired) {
                checkCameraAndStartScan()
            } else {
                executeAlarmDismiss()
            }
        }

        btnCancelQrScan.setOnClickListener {
            stopQrScan()
        }
    }

    private fun checkCameraAndStartScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startQrScan()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQrScan()
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startQrScan() {
        isScanning = true
        qrScannerOverlay.visibility = View.VISIBLE
        qrPromptText.text = getString(R.string.alarm_qr_scan_prompt)
        barcodeScannerView.resume()

        val expected = QrAlarmHelper.getExpectedQrValue(this)
        barcodeScannerView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val scanned = result?.text ?: return
                if (QrAlarmHelper.isQrMatch(scanned, expected)) {
                    stopQrScan()
                    Toast.makeText(this@AlarmAlertActivity, getString(R.string.alarm_qr_success), Toast.LENGTH_SHORT).show()
                    executeAlarmDismiss()
                } else {
                    runOnUiThread {
                        qrPromptText.text = getString(R.string.alarm_qr_mismatch)
                    }
                }
            }
        })
    }

    private fun stopQrScan() {
        isScanning = false
        barcodeScannerView.pause()
        qrScannerOverlay.visibility = View.GONE
    }

    private fun executeAlarmDismiss() {
        if (isReading) return
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
        val styleId = currentAlarmItem?.speakerId ?: resolveSpeakerId(resolvedAlarmId)
        
        if (resolvedAlarmId != null) {
            rescheduleNextAlarm(resolvedAlarmId)
        }
        startMorningReading(styleId)
    }

    private fun resolveAlarmItem(alarmId: String?): AlarmItem? {
        if (alarmId == null) return null
        val prefs = getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return null
        return try {
            val alarmList = Json.decodeFromString<List<AlarmItem>>(jsonString)
            alarmList.find { it.id == alarmId }
        } catch (e: Exception) {
            null
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

    private fun updateCurrentTime() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        alertTimeText.text = sdf.format(Date())
    }

    private fun rescheduleNextAlarm(alarmId: String) {
        val prefs = getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return
        try {
            val alarmList = Json.decodeFromString<List<AlarmItem>>(jsonString).toMutableList()
            val index = alarmList.indexOfFirst { it.id == alarmId }
            if (index != -1) {
                val item = alarmList[index]
                if (item.isOneShot) {
                    alarmList.removeAt(index)
                    prefs.edit().putString(CuraConstants.KEY_ALARM_LIST, Json.encodeToString(alarmList)).apply()
                } else if (item.repeatDays.isNotEmpty()) {
                    scheduleNextRepeat(item)
                } else {
                    item.isEnabled = false
                    prefs.edit().putString(CuraConstants.KEY_ALARM_LIST, Json.encodeToString(alarmList)).apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun scheduleNextRepeat(item: AlarmItem) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", item.id)
            val file = File(filesDir, "${item.id}_alarm.wav")
            putExtra("AUDIO_PATH", file.absolutePath)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(this, item.id.hashCode(), intent, flags)

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
