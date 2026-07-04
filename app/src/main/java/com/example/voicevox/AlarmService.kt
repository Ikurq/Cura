package com.example.voicevox // ※ご主人様のパッケージ名に合わせてね！

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🚨 通知の「停止」ボタンが押されたとき、このServiceに「STOP_ACTION」という合図が届くよ！
        if (intent?.action == "STOP_ACTION") {
            stopSelf() // 自分自身を終了してアラームを止めるよ
            return START_NOT_STICKY
        }

        createNotificationChannel()

        val audioFilePath = intent?.getStringExtra("AUDIO_FILE_PATH")
        val alarmId = intent?.getStringExtra("ALARM_ID")

        // 🔔 通知欄の「停止」ボタンを押したときに、自分自身（AlarmService）に合図を送るための手紙（PendingIntent）
        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = "STOP_ACTION"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 🚀 アラーム画面を表示するためのインテント
        val alertIntent = Intent(this, AlarmAlertActivity::class.java).apply {
            putExtra("AUDIO_FILE_PATH", audioFilePath)
            putExtra("ALARM_ID", alarmId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 📝 通知の作成
        val notification: Notification = NotificationCompat.Builder(this, "ALARM_CHANNEL")
            .setContentTitle("アラーム鳴動中")
            .setContentText("アラームが鳴っています。")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true) // ここが重要！
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        if (audioFilePath != null) {
            val audioFile = File(audioFilePath)
            if (audioFile.exists()) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )
                    setDataSource(applicationContext, Uri.fromFile(audioFile))
                    setVolume(1.0f, 1.0f) // 最大音量に設定
                    setOnPreparedListener {
                        it.isLooping = true
                        it.start()
                    }
                    prepareAsync()
                }
            }
        }

        // 📳 バイブレーションの処理
        val shouldVibrate = intent?.getBooleanExtra("VIBRATE", true) ?: true
        if (shouldVibrate) {
            startVibration()
        }

        return START_NOT_STICKY
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 1000, 1000) // 1秒鳴らして1秒休む
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        vibrator?.cancel()
        vibrator = null
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
