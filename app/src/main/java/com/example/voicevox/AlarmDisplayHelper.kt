package com.example.voicevox

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

data class AlarmLaunchContext(
    val sdkInt: Int,
    val isInteractive: Boolean,
    val isKeyguardLocked: Boolean,
    val isAppInForeground: Boolean,
    val canUseFullScreenIntent: Boolean,
    val canDrawOverlays: Boolean,
    val hasPostNotifications: Boolean
)

data class AlarmLaunchPlan(
    val attachFullScreenIntent: Boolean,
    val tryDirectActivityLaunch: Boolean,
    val useOverlayFallback: Boolean,
    val overlayDelayMillis: Long,
    val requiresPermissionAttention: Boolean
)

enum class MissingAlarmPermission {
    POST_NOTIFICATIONS,
    FULL_SCREEN_INTENT,
    OVERLAY,
    EXACT_ALARM,
    BATTERY_OPTIMIZATION
}

data class AlarmPermissionStatus(
    val hasPostNotifications: Boolean,
    val canUseFullScreenIntent: Boolean,
    val canDrawOverlays: Boolean,
    val canScheduleExactAlarms: Boolean,
    val isIgnoringBatteryOptimizations: Boolean
) {
    val isLockScreenOccupancyReady: Boolean
        get() = hasPostNotifications && canUseFullScreenIntent

    val isUnlockedOccupancyReady: Boolean
        get() = isLockScreenOccupancyReady && canDrawOverlays

    val hasAnyMissingCriticalPermission: Boolean
        get() = !hasPostNotifications || !canUseFullScreenIntent || !canScheduleExactAlarms
}

/**
 * アラーム鳴動時の画面占有（Full-Screen Intent / 直接起動 / オーバーレイフォールバック）および
 * 必要なAndroid OS権限の判定・誘導を一元管理するヘルパー。
 */
object AlarmDisplayHelper {
    private const val TAG = "AlarmDisplayHelper"

    const val ALARM_CHANNEL_ID = "ALARM_CHANNEL_V2"
    const val LEGACY_ALARM_CHANNEL_ID = "ALARM_CHANNEL"
    const val ALARM_NOTIFICATION_ID = 1

    private const val OVERLAY_DELAY_UNLOCKED_MS = 450L
    private const val OVERLAY_DELAY_MISSING_PERM_MS = 350L

    /**
     * 端末状態・権限状態から、アラーム画面を占有表示するための最適な起動計画を算出する。
     */
    fun decideLaunchPlan(ctx: AlarmLaunchContext): AlarmLaunchPlan {
        val unlockedOtherApp = ctx.isInteractive && !ctx.isKeyguardLocked && !ctx.isAppInForeground
        val fsiBlocked = !ctx.canUseFullScreenIntent || !ctx.hasPostNotifications

        val tryDirect = ctx.isAppInForeground ||
            ctx.canDrawOverlays ||
            ctx.sdkInt < Build.VERSION_CODES.Q ||
            (!ctx.isKeyguardLocked && !ctx.hasPostNotifications)

        // 画面点灯＆ロック解除中に他アプリを使っている場合、OS仕様上 setFullScreenIntent はバナーに格下げされるため、
        // オーバーレイ権限がある場合は可視オーバーレイ橋渡し＆全画面フォールバックを有効化する。
        val useOverlay = ctx.canDrawOverlays && !ctx.isAppInForeground && (unlockedOtherApp || fsiBlocked)
        val overlayDelay = when {
            !useOverlay -> 0L
            fsiBlocked -> OVERLAY_DELAY_MISSING_PERM_MS
            else -> OVERLAY_DELAY_UNLOCKED_MS
        }

        val needsPermission = (ctx.sdkInt >= Build.VERSION_CODES.TIRAMISU && !ctx.hasPostNotifications) ||
            (ctx.sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !ctx.canUseFullScreenIntent)

        return AlarmLaunchPlan(
            attachFullScreenIntent = true,
            tryDirectActivityLaunch = tryDirect,
            useOverlayFallback = useOverlay,
            overlayDelayMillis = overlayDelay,
            requiresPermissionAttention = needsPermission
        )
    }

    fun resolveFullScreenSettingsAction(sdkInt: Int): String {
        return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
    }

    fun resolveExactAlarmSettingsAction(sdkInt: Int): String? {
        return if (sdkInt >= Build.VERSION_CODES.S) {
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
        } else {
            null
        }
    }

    fun getMissingPermissions(status: AlarmPermissionStatus): List<MissingAlarmPermission> {
        val missing = mutableListOf<MissingAlarmPermission>()
        if (!status.hasPostNotifications) missing.add(MissingAlarmPermission.POST_NOTIFICATIONS)
        if (!status.canUseFullScreenIntent) missing.add(MissingAlarmPermission.FULL_SCREEN_INTENT)
        if (!status.canScheduleExactAlarms) missing.add(MissingAlarmPermission.EXACT_ALARM)
        if (!status.canDrawOverlays) missing.add(MissingAlarmPermission.OVERLAY)
        if (!status.isIgnoringBatteryOptimizations) missing.add(MissingAlarmPermission.BATTERY_OPTIMIZATION)
        return missing
    }

    fun captureLaunchContext(context: Context): AlarmLaunchContext {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val status = evaluatePermissions(context)

        return AlarmLaunchContext(
            sdkInt = Build.VERSION.SDK_INT,
            isInteractive = powerManager?.isInteractive ?: true,
            isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false,
            isAppInForeground = isAppInForeground(context),
            canUseFullScreenIntent = status.canUseFullScreenIntent,
            canDrawOverlays = status.canDrawOverlays,
            hasPostNotifications = status.hasPostNotifications
        )
    }

    fun evaluatePermissions(context: Context): AlarmPermissionStatus {
        val hasPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager?.canUseFullScreenIntent() ?: false
        } else {
            true
        }

        val canDrawOverlays = Settings.canDrawOverlays(context)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

        return AlarmPermissionStatus(
            hasPostNotifications = hasPostNotifications,
            canUseFullScreenIntent = canUseFullScreenIntent,
            canDrawOverlays = canDrawOverlays,
            canScheduleExactAlarms = canScheduleExactAlarms,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations
        )
    }

    fun isAppInForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val appProcesses = am.runningAppProcesses ?: return false
        val pkgName = context.packageName
        return appProcesses.any { process ->
            process.processName == pkgName &&
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    /**
     * アラーム鳴動用の高重要度・DNDバイパス・ロック画面公開チャンネルを準備する。
     * 旧チャンネルにキャッシュされた制約を避けるため新IDへ移行する。
     */
    fun ensureAlarmNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 旧チャンネルが存在する場合は削除して設定不整合を防ぐ
        if (manager.getNotificationChannel(LEGACY_ALARM_CHANNEL_ID) != null) {
            try {
                manager.deleteNotificationChannel(LEGACY_ALARM_CHANNEL_ID)
            } catch (_: Exception) {
                // 削除不可な端末状態は無視
            }
        }

        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            context.getString(R.string.alarm_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.alarm_notification_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            // アラーム音声とバイブは AlarmService 側で制御するため通知側の二重再生を防ぐ
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildAlertIntent(context: Context, alarmId: String?): Intent {
        return Intent(context, AlarmAlertActivity::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
    }

    fun buildFullScreenPendingIntent(context: Context, alarmId: String?): PendingIntent {
        val alertIntent = buildAlertIntent(context, alarmId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            return PendingIntent.getActivity(context, 0, alertIntent, flags, options.toBundle())
        }
        return PendingIntent.getActivity(context, 0, alertIntent, flags)
    }

    fun buildAlarmNotification(
        context: Context,
        fullScreenPendingIntent: PendingIntent,
        stopPendingIntent: PendingIntent,
        allowNotificationStop: Boolean = true
    ): Notification {
        val builder = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(context.getString(R.string.alarm_notification_text))
            .setSmallIcon(R.drawable.ic_notification_cura)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (allowNotificationStop) {
            builder.addAction(
                android.R.drawable.ic_lock_power_off,
                context.getString(R.string.alarm_notification_stop),
                stopPendingIntent
            )
        }
        return builder.build()
    }

    /**
     * Activity の直接起動を試行する。BAL 制限等で拒否された場合でも例外を握りつぶし安全に継続する。
     */
    fun tryLaunchAlertActivity(
        context: Context,
        alarmId: String?,
        fullScreenPendingIntent: PendingIntent? = null
    ): Boolean {
        val alertIntent = buildAlertIntent(context, alarmId)

        // 1. Android 14+ では ActivityOptions 付き PendingIntent.send を優先試行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && fullScreenPendingIntent != null) {
            try {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                fullScreenPendingIntent.send(context, 0, null, null, null, null, options.toBundle())
                return true
            } catch (e: Exception) {
                Log.w(TAG, "PendingIntent BAL launch attempt failed: ${e.message}")
            }
        }

        // 2. フォールバックとして startActivity を試行
        return try {
            context.startActivity(alertIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity failed: ${e.message}")
            false
        }
    }

    fun openFullScreenIntentSettings(context: Context) {
        val action = resolveFullScreenSettingsAction(Build.VERSION.SDK_INT)
        val primary = Intent(action, "package:${context.packageName}".toUri())
        openSettingsSafely(context, primary)
    }

    fun openOverlaySettings(context: Context) {
        val primary = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri()
        )
        openSettingsSafely(context, primary)
    }

    fun openNotificationSettings(context: Context) {
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        }
        openSettingsSafely(context, primary)
    }

    fun openExactAlarmSettings(context: Context) {
        val action = resolveExactAlarmSettingsAction(Build.VERSION.SDK_INT)
        if (action != null) {
            val primary = Intent(action, "package:${context.packageName}".toUri())
            openSettingsSafely(context, primary)
        } else {
            openAppDetailsSettings(context)
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val primary = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri()
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        openSettingsSafely(context, primary, fallback)
    }

    fun openAppDetailsSettings(context: Context) {
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri()
        )
        if (context !is android.app.Activity) {
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(details)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details settings", e)
        }
    }

    private fun openSettingsSafely(context: Context, primary: Intent, fallback: Intent? = null) {
        if (context !is android.app.Activity) {
            primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(primary)
        } catch (e: Exception) {
            Log.w(TAG, "Primary settings intent failed (${primary.action}): ${e.message}")
            if (fallback != null) {
                try {
                    context.startActivity(fallback)
                    return
                } catch (e2: Exception) {
                    Log.w(TAG, "Fallback settings intent failed: ${e2.message}")
                }
            }
            openAppDetailsSettings(context)
        }
    }
}
