package com.example.voicevox

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_STOP = "STOP_ACTION"
        const val ACTION_DISMISS_OVERLAY = "jp.cura.action.DISMISS_ALARM_OVERLAY"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val SCREEN_WAKELOCK_TIMEOUT_MS = 3_000L
        private const val LOCK_FALLBACK_CHECK_DELAY_MS = 1_200L

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var currentAlarmId: String? = null
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isStopping: Boolean = false
    private var hasAcquiredVolume: Boolean = false
    private var isDismissReceiverRegistered: Boolean = false

    private val dismissOverlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS_OVERLAY) {
                removeOverlay()
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            requestAggressiveFocus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(ACTION_DISMISS_OVERLAY)
        ContextCompat.registerReceiver(
            this,
            dismissOverlayReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isDismissReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isStopping = true
            mainHandler.removeCallbacksAndMessages(null)
            AlarmScheduler.handleAlarmDismissed(this, currentAlarmId)
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        isStopping = false
        isRunning = true

        acquireWakeLocks()
        requestAggressiveFocus()

        // 鳴動中の別アラーム再入時に参照カウントがリークしないよう1セッション1回のみ取得する
        if (!hasAcquiredVolume) {
            AlarmVolumeManager.acquireAlarmVolume(this)
            hasAcquiredVolume = true
        }

        sendBroadcast(Intent(CuraConstants.ACTION_ALARM_STARTED))
        AlarmDisplayHelper.ensureAlarmNotificationChannel(this)

        val audioFilePath = AlarmScheduler.resolveAudioFilePath(intent ?: Intent())
        val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        currentAlarmId = alarmId

        val alarmItem = AlarmScheduler.findAlarmItem(this, alarmId)
        val allowNotificationStop = alarmItem?.requireQrCode != true

        val stopIntent = Intent(this, AlarmService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenPendingIntent = AlarmDisplayHelper.buildFullScreenPendingIntent(this, alarmId)
        val notification = AlarmDisplayHelper.buildAlarmNotification(
            this,
            fullScreenPendingIntent,
            stopPendingIntent,
            allowNotificationStop = allowNotificationStop
        )

        startForegroundWithType(notification)
        startAlarmAudio(audioFilePath)

        val shouldVibrate = intent?.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true) ?: true
        if (shouldVibrate) startVibration()

        dispatchAlarmDisplay(alarmId, alarmItem, fullScreenPendingIntent)

        return START_NOT_STICKY
    }

    private fun startAlarmAudio(audioFilePath: String?) {
        val customFile = audioFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
        val audioUri: Uri? = if (customFile != null) {
            Uri.fromFile(customFile)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
        if (audioUri == null) return

        stopAndReleaseMediaPlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(applicationContext, audioUri)
                setVolume(1.0f, 1.0f)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm audio", e)
        }
    }

    private fun stopAndReleaseMediaPlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer stop ignored: ${e.message}")
        }
        try {
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer release ignored: ${e.message}")
        }
    }

    private fun startForegroundWithType(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AlarmDisplayHelper.ALARM_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(AlarmDisplayHelper.ALARM_NOTIFICATION_ID, notification)
        }
    }

    private fun dispatchAlarmDisplay(
        alarmId: String?,
        alarmItem: AlarmItem?,
        fullScreenPendingIntent: PendingIntent
    ) {
        mainHandler.removeCallbacksAndMessages(null)

        // すでにオーバーレイ表示中に別アラームが鳴った場合は内容を即時更新する
        overlayView?.let { existing ->
            bindOverlayContent(existing, alarmId, alarmItem, fullScreenPendingIntent)
        }

        val launchContext = AlarmDisplayHelper.captureLaunchContext(this)
        val plan = AlarmDisplayHelper.decideLaunchPlan(launchContext)

        if (plan.tryDirectActivityLaunch) {
            AlarmDisplayHelper.tryLaunchAlertActivity(this, alarmId, fullScreenPendingIntent)
        }

        if (plan.useOverlayFallback) {
            mainHandler.postDelayed({
                if (isRunning && !isStopping && !AlarmAlertActivity.isActiveInSession &&
                    Settings.canDrawOverlays(this)
                ) {
                    showAlarmOverlay(alarmId, alarmItem, fullScreenPendingIntent)
                }
            }, plan.overlayDelayMillis)
        } else if (launchContext.canDrawOverlays) {
            // ロック画面/画面OFF時はFullScreenIntentを優先するが、解除後に万一未起動のままなら救済する
            mainHandler.postDelayed({
                if (!isRunning || isStopping || AlarmAlertActivity.isActiveInSession) return@postDelayed
                val currentCtx = AlarmDisplayHelper.captureLaunchContext(this)
                if (currentCtx.isInteractive && !currentCtx.isKeyguardLocked &&
                    !currentCtx.isAppInForeground && currentCtx.canDrawOverlays
                ) {
                    showAlarmOverlay(alarmId, alarmItem, fullScreenPendingIntent)
                }
            }, LOCK_FALLBACK_CHECK_DELAY_MS)
        }
    }

    private fun showAlarmOverlay(
        alarmId: String?,
        alarmItem: AlarmItem?,
        fullScreenPendingIntent: PendingIntent
    ) {
        if (!Settings.canDrawOverlays(this)) return

        overlayView?.let { existing ->
            bindOverlayContent(existing, alarmId, alarmItem, fullScreenPendingIntent)
            return
        }

        try {
            val view = LayoutInflater.from(this).inflate(R.layout.layout_alarm_overlay, null)
            bindOverlayContent(view, alarmId, alarmItem, fullScreenPendingIntent)

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            windowManager?.addView(view, params)
            overlayView = view

            // Android 15では可視オーバーレイが存在する状態ならActivity前面起動(BAL)が許可される
            view.post {
                if (isRunning && !isStopping && !AlarmAlertActivity.isActiveInSession) {
                    AlarmDisplayHelper.tryLaunchAlertActivity(this, alarmId, fullScreenPendingIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display alarm overlay", e)
            overlayView = null
        }
    }

    private fun bindOverlayContent(
        view: View,
        alarmId: String?,
        alarmItem: AlarmItem?,
        fullScreenPendingIntent: PendingIntent
    ) {
        val overlayTime = view.findViewById<TextView>(R.id.overlayTime)
        val overlayMessage = view.findViewById<TextView>(R.id.overlayMessage)
        val overlayQrBadge = view.findViewById<TextView>(R.id.overlayQrBadge)
        val btnOpenAlert = view.findViewById<Button>(R.id.btnOverlayOpenAlert)
        val btnStopAlarm = view.findViewById<Button>(R.id.btnOverlayStopAlarm)

        overlayTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        overlayMessage.text = alarmItem?.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.alarm_alert_default_message)

        val isQrRequired = alarmItem?.requireQrCode == true
        if (isQrRequired) {
            overlayQrBadge.visibility = View.VISIBLE
            btnOpenAlert.text = getString(R.string.alarm_scan_qr_button)
            btnStopAlarm.visibility = View.GONE
        } else {
            overlayQrBadge.visibility = View.GONE
            btnOpenAlert.text = getString(R.string.alarm_overlay_open_screen)
            btnStopAlarm.visibility = View.VISIBLE
        }

        // 先にオーバーレイを消すとAndroid 15の可視ウィンドウBAL要件を失うため、起動完了後にActivity側から消去する
        btnOpenAlert.setOnClickListener {
            val launched = AlarmDisplayHelper.tryLaunchAlertActivity(this, alarmId, fullScreenPendingIntent)
            if (!launched) {
                val alertIntent = AlarmDisplayHelper.buildAlertIntent(this, alarmId)
                try {
                    startActivity(alertIntent)
                } catch (e: Exception) {
                    Log.w(TAG, "Overlay open alert failed: ${e.message}")
                }
            }
        }

        btnStopAlarm.setOnClickListener {
            isStopping = true
            AlarmScheduler.handleAlarmDismissed(this, alarmId)
            removeOverlay()
            stopSelf()
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove alarm overlay: ${e.message}")
        }
    }

    private fun acquireWakeLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (cpuWakeLock == null) {
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cura:AlarmServiceCpuLock").apply {
                setReferenceCounted(false)
            }
        }
        if (cpuWakeLock?.isHeld != true) {
            cpuWakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        }

        if (!pm.isInteractive) {
            try {
                @Suppress("DEPRECATION")
                if (screenWakeLock == null) {
                    screenWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "Cura:AlarmServiceScreenLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                if (screenWakeLock?.isHeld != true) {
                    screenWakeLock?.acquire(SCREEN_WAKELOCK_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Screen wakeup lock unavailable: ${e.message}")
            }
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun requestAggressiveFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 200, 100, 200, 100, 500, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        }
    }

    override fun onDestroy() {
        isStopping = true
        isRunning = false
        currentAlarmId = null
        mainHandler.removeCallbacksAndMessages(null)
        removeOverlay()

        if (isDismissReceiverRegistered) {
            try {
                unregisterReceiver(dismissOverlayReceiver)
            } catch (_: Exception) {}
            isDismissReceiverRegistered = false
        }

        stopAndReleaseMediaPlayer()
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audioManager?.abandonAudioFocus(focusChangeListener)
            }
        } catch (_: Exception) {}

        if (hasAcquiredVolume) {
            AlarmVolumeManager.releaseAlarmVolume(this)
            hasAcquiredVolume = false
        }

        releaseWakeLocks()
        sendBroadcast(Intent(CuraConstants.ACTION_ALARM_DISMISSED).setPackage(packageName))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
