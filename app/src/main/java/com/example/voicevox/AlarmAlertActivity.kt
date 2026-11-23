package com.example.voicevox

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AlarmAlertActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_STYLE_ID = 3
        private const val REQUEST_CAMERA_CODE = 1001

        @Volatile
        var isActiveInSession: Boolean = false

        @Volatile
        var isCurrentlyVisible: Boolean = false
    }

    private lateinit var alertTimeText: TextView
    private lateinit var alertMessageText: TextView
    private lateinit var stopAlarmNowButton: Button
    private lateinit var alertQrBadge: TextView
    private lateinit var qrScannerOverlay: LinearLayout
    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private lateinit var qrPromptText: TextView
    private lateinit var btnCancelQrScan: Button

    private var mediaPlayer: MediaPlayer? = null
    private var readingJob: Job? = null
    private var isReading: Boolean = false
    private var isScanning: Boolean = false
    private var hasAcquiredVolume: Boolean = false
    private var isDismissReceiverRegistered: Boolean = false
    private var currentAlarmItem: AlarmItem? = null

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val alarmDismissedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CuraConstants.ACTION_ALARM_DISMISSED && !isReading && !isFinishing) {
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        dismissAlarmOverlay()

        // モーニング読み上げ中に別アラームが鳴り始めた場合は、前の読み上げを中断して新アラーム画面に切り替える
        if (isReading) {
            readingJob?.cancel()
            readingJob = null
            stopAndReleaseMediaPlayer()
            isReading = false
            stopAlarmNowButton.isEnabled = true
        }
        updateCurrentTime()
        applyAlarmToUi(intent)
    }

    override fun onResume() {
        super.onResume()
        isCurrentlyVisible = true
        dismissAlarmOverlay()
        if (isScanning) {
            barcodeScannerView.resume()
        }
        if (!isReading && !AlarmService.isRunning && !isServiceRunning(this, AlarmService::class.java)) {
            finish()
        }
    }

    override fun onPause() {
        isCurrentlyVisible = false
        super.onPause()
        if (isScanning) {
            barcodeScannerView.pause()
        }
    }

    override fun onDestroy() {
        isCurrentlyVisible = false
        isActiveInSession = false
        readingJob?.cancel()
        readingJob = null

        if (isDismissReceiverRegistered) {
            try {
                unregisterReceiver(alarmDismissedReceiver)
            } catch (_: Exception) {}
            isDismissReceiverRegistered = false
        }

        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
        if (isScanning) {
            barcodeScannerView.pause()
        }
        stopAndReleaseMediaPlayer()

        if (hasAcquiredVolume) {
            AlarmVolumeManager.releaseAlarmVolume(this)
            hasAcquiredVolume = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActiveInSession = true

        if (!hasAcquiredVolume) {
            AlarmVolumeManager.acquireAlarmVolume(this)
            hasAcquiredVolume = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // ロック画面上に直接全画面表示させる（無条件のrequestDismissKeyguardはPIN入力画面が割り込むため呼ばない）
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm_alert)
        sendBroadcast(Intent(CuraConstants.ACTION_ALARM_STARTED))
        dismissAlarmOverlay()

        ContextCompat.registerReceiver(
            this,
            alarmDismissedReceiver,
            IntentFilter(CuraConstants.ACTION_ALARM_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isDismissReceiverRegistered = true

        alertTimeText = findViewById(R.id.alertTime)
        alertMessageText = findViewById(R.id.alertMessage)
        stopAlarmNowButton = findViewById(R.id.stopAlarmNowButton)
        alertQrBadge = findViewById(R.id.alertQrBadge)
        qrScannerOverlay = findViewById(R.id.qrScannerOverlay)
        barcodeScannerView = findViewById(R.id.barcodeScannerView)
        qrPromptText = findViewById(R.id.qrPromptText)
        btnCancelQrScan = findViewById(R.id.btnCancelQrScan)

        updateCurrentTime()
        timeHandler.post(timeRunnable)

        applyAlarmToUi(intent)

        stopAlarmNowButton.setOnClickListener {
            if (isReading) return@setOnClickListener

            if (currentAlarmItem?.requireQrCode == true) {
                checkCameraAndStartScan()
            } else {
                executeAlarmDismiss()
            }
        }

        btnCancelQrScan.setOnClickListener {
            stopQrScan()
        }
    }

    private fun applyAlarmToUi(targetIntent: Intent?) {
        val resolvedAlarmId = targetIntent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
            ?: AlarmService.currentAlarmId
        currentAlarmItem = AlarmScheduler.findAlarmItem(this, resolvedAlarmId)

        alertMessageText.text = currentAlarmItem?.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.alarm_alert_default_message)

        val isQrRequired = currentAlarmItem?.requireQrCode == true
        if (isQrRequired) {
            alertQrBadge.visibility = View.VISIBLE
            stopAlarmNowButton.text = getString(R.string.alarm_scan_qr_button)
        } else {
            alertQrBadge.visibility = View.GONE
            stopAlarmNowButton.text = getString(R.string.alarm_stop_button)
        }
    }

    private fun dismissAlarmOverlay() {
        sendBroadcast(Intent(AlarmService.ACTION_DISMISS_OVERLAY).setPackage(packageName))
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
        stopAlarmNowButton.text = getString(R.string.alarm_checking_schedule)
        alertTimeText.text = getString(R.string.alarm_stopped_title)

        val resolvedAlarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: AlarmService.currentAlarmId
        val styleId = currentAlarmItem?.speakerId ?: DEFAULT_STYLE_ID

        val serviceIntent = Intent(this, AlarmService::class.java)
        stopService(serviceIntent)

        // 起床統計とキャラクター経験値を付与
        updateWakeUpStatistics()
        addCharExp()

        AlarmScheduler.handleAlarmDismissed(this, resolvedAlarmId)
        startMorningReading(styleId)
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
        readingJob?.cancel()
        readingJob = lifecycleScope.launch {
            try {
                val events = ScheduleLoader.loadAllEventsForToday(this@AlarmAlertActivity, Calendar.getInstance())
                val shouldReadTasks = currentAlarmItem?.readTasks ?: true
                val tasks = if (shouldReadTasks) {
                    ScheduleLoader.loadTasksForToday(this@AlarmAlertActivity)
                } else {
                    emptyList()
                }

                val message = AlarmTemplateManager.buildMorningReadingMessage(
                    context = this@AlarmAlertActivity,
                    events = events,
                    tasks = tasks,
                    now = Calendar.getInstance()
                )

                val outputFile = File(cacheDir, "morning_reading.wav")

                stopAlarmNowButton.apply {
                    isEnabled = false
                    text = getString(R.string.alarm_reading_button)
                }
                alertTimeText.text = getString(R.string.alarm_reading_title)

                val success = withContext(Dispatchers.IO) {
                    CuraVoicevox.createAudio(
                        this@AlarmAlertActivity,
                        message,
                        styleId.toString(),
                        outputFile,
                        volumeScale = 1.6f
                    )
                }

                if (!isReading) return@launch
                if (success && outputFile.exists() && outputFile.length() > 0) {
                    playAudio(outputFile)
                } else {
                    finish()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AlarmAlertActivity", "Error during morning reading", e)
                if (isReading) finish()
            }
        }
    }

    private fun playAudio(file: File) {
        try {
            stopAndReleaseMediaPlayer()
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
                    if (isReading) finish()
                }
                setOnErrorListener { _, _, _ ->
                    if (isReading) finish()
                    true
                }
                setOnPreparedListener {
                    if (isReading) it.start()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            android.util.Log.e("AlarmAlertActivity", "Failed to play morning reading audio", e)
            finish()
        }
    }

    private fun stopAndReleaseMediaPlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (_: Exception) {}
        try {
            player.release()
        } catch (_: Exception) {}
    }

    private fun updateCurrentTime() {
        if (isReading) return
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        alertTimeText.text = sdf.format(Date())
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
            Toast.makeText(this, getString(R.string.alarm_wakeup_praise_short), Toast.LENGTH_SHORT).show()
            return
        }

        val expPerLevel = CuraMessageManager.getIntConstant(this, "exp_per_level", 100).toLong()
        val gain = expPerLevel

        prefs.edit()
            .putLong(CuraConstants.KEY_PENDING_EXP, currentPending + gain)
            .putLong("last_alarm_reward_millis", now)
            .apply()

        Toast.makeText(this, getString(R.string.alarm_wakeup_praise_reward), Toast.LENGTH_LONG).show()
    }

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
