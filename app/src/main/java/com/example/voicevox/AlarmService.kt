package com.example.voicevox // ※ご主人様のパッケージ名に合わせてね！

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import java.io.File

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            // 他のアプリにフォーカスを奪われたら、再度奪い返す（アグレッシブ！）
            requestAggressiveFocus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_ACTION") {
            stopSelf()
            return START_NOT_STICKY
        }

        requestAggressiveFocus()
        createNotificationChannel()

        val audioFilePath = intent?.getStringExtra("AUDIO_FILE_PATH")
        val alarmId = intent?.getStringExtra("ALARM_ID")

        val stopIntent = Intent(this, AlarmService::class.java).apply { action = "STOP_ACTION" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val alertIntent = Intent(this, AlarmAlertActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, alertIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, "ALARM_CHANNEL")
            .setContentTitle("【🚨警報】キュラが呼んでいます！")
            .setContentText("YouTubeを止めて起きてください！")
            .setSmallIcon(R.drawable.ic_notification_cura)
            .setPriority(NotificationCompat.PRIORITY_MAX) // 最高優先度
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        startForeground(1, notification)

        if (audioFilePath != null) {
            val audioFile = File(audioFilePath)
            if (audioFile.exists()) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build())
                    setDataSource(applicationContext, Uri.fromFile(audioFile))
                    setVolume(1.0f, 1.0f)
                    isLooping = true
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }
        }

        val shouldVibrate = intent?.getBooleanExtra("VIBRATE", true) ?: true
        if (shouldVibrate) startVibration()

        return START_NOT_STICKY
    }

    private fun requestAggressiveFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(focusChangeListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // より強力な振動パターン（短く鋭い連打）
        val pattern = longArrayOf(0, 200, 100, 200, 100, 500, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION") audioManager?.abandonAudioFocus(focusChangeListener)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ALARM_CHANNEL",
                "Alarm Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
