package com.example.voicevox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.TimeZone

data class AlarmDismissUpdateResult(
    val updatedList: List<AlarmItem>,
    val itemToReschedule: AlarmItem?,
    val didModifyList: Boolean
)

/**
 * アラームの次回発火時刻計算、停止時のライフサイクル更新、および AlarmManager によるスケジューリングを担うヘルパー。
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    const val EXTRA_ALARM_ID = "ALARM_ID"
    const val EXTRA_AUDIO_FILE_PATH = "AUDIO_FILE_PATH"
    const val EXTRA_LEGACY_AUDIO_PATH = "AUDIO_PATH"
    const val EXTRA_VIBRATE = "VIBRATE"

    private const val SHOW_INTENT_REQUEST_OFFSET = 0x10000
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 指定された時刻・繰り返し曜日から、現在時刻より未来の次回アラーム発火時刻(ms)を算出する。
     */
    fun calculateNextTriggerMillis(
        hour: Int,
        minute: Int,
        repeatDays: List<Int>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val baseCal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val validRepeatDays = repeatDays.filter { it in Calendar.SUNDAY..Calendar.SATURDAY }.distinct()
        if (validRepeatDays.isEmpty()) {
            if (baseCal.timeInMillis <= nowMillis) {
                baseCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return baseCal.timeInMillis
        }

        var earliestMillis = Long.MAX_VALUE
        for (day in validRepeatDays) {
            val candidate = (baseCal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_WEEK, day)
                if (timeInMillis <= nowMillis) {
                    add(Calendar.WEEK_OF_YEAR, 1)
                }
            }
            if (candidate.timeInMillis < earliestMillis) {
                earliestMillis = candidate.timeInMillis
            }
        }
        return earliestMillis
    }

    fun resolveAudioFilePath(primaryPath: String?, legacyPath: String?): String? {
        if (!primaryPath.isNullOrEmpty()) return primaryPath
        if (!legacyPath.isNullOrEmpty()) return legacyPath
        return null
    }

    fun resolveAudioFilePath(intent: Intent): String? {
        return resolveAudioFilePath(
            intent.getStringExtra(EXTRA_AUDIO_FILE_PATH),
            intent.getStringExtra(EXTRA_LEGACY_AUDIO_PATH)
        )
    }

    /**
     * 保存済みアラーム一覧から指定IDのアラームを取得する。
     */
    fun findAlarmItem(context: Context, alarmId: String?): AlarmItem? {
        if (alarmId.isNullOrBlank()) return null
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return null
        return try {
            json.decodeFromString<List<AlarmItem>>(jsonStr).find { it.id == alarmId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse alarm list", e)
            null
        }
    }

    /**
     * アラーム停止時のリスト更新および次回再予約対象を算出する純粋ロジック。
     */
    fun applyAlarmDismissedState(alarmList: List<AlarmItem>, alarmId: String): AlarmDismissUpdateResult {
        val index = alarmList.indexOfFirst { it.id == alarmId }
        if (index == -1) {
            return AlarmDismissUpdateResult(alarmList, null, didModifyList = false)
        }

        val item = alarmList[index]
        return when {
            item.isOneShot -> {
                val nextList = alarmList.toMutableList().apply { removeAt(index) }
                AlarmDismissUpdateResult(nextList, itemToReschedule = null, didModifyList = true)
            }
            item.repeatDays.isNotEmpty() -> {
                AlarmDismissUpdateResult(alarmList, itemToReschedule = item, didModifyList = false)
            }
            else -> {
                val nextList = alarmList.toMutableList().apply {
                    this[index] = item.copy(isEnabled = false)
                }
                AlarmDismissUpdateResult(nextList, itemToReschedule = null, didModifyList = true)
            }
        }
    }

    /**
     * どの停止経路（画面ボタン、オーバーレイ、通知の強制停止）から停止されても
     * ワンショット削除・繰り返し再予約・単発無効化を一貫して反映する。
     */
    fun handleAlarmDismissed(context: Context, alarmId: String?) {
        if (alarmId.isNullOrBlank()) return
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return

        try {
            val currentList = json.decodeFromString<List<AlarmItem>>(jsonStr)
            val result = applyAlarmDismissedState(currentList, alarmId)
            if (result.didModifyList) {
                prefs.edit().putString(CuraConstants.KEY_ALARM_LIST, json.encodeToString(result.updatedList)).apply()
                AlarmWidgetProvider.triggerUpdate(context)
            }
            result.itemToReschedule?.let { item ->
                val audioFile = File(context.filesDir, "${item.id}_alarm.wav")
                scheduleAlarm(context, item, audioFile.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update alarm state on dismiss id=$alarmId", e)
        }
    }

    /**
     * アラーム時計 API (setAlarmClock) を用いて Doze 制限を受けずにアラームをスケジュールする。
     */
    fun scheduleAlarm(
        context: Context,
        item: AlarmItem,
        audioPath: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerMillis = calculateNextTriggerMillis(item.hour, item.minute, item.repeatDays, nowMillis)

        val receiverIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, item.id)
            putExtra(EXTRA_AUDIO_FILE_PATH, audioPath)
            putExtra(EXTRA_VIBRATE, item.vibrate)
        }
        val operationPendingIntent = PendingIntent.getBroadcast(
            context,
            item.id.hashCode(),
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            item.id.hashCode() xor SHOW_INTENT_REQUEST_OFFSET,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val info = AlarmManager.AlarmClockInfo(triggerMillis, showPendingIntent)
            am.setAlarmClock(info, operationPendingIntent)
            Log.d(TAG, "Scheduled alarm clock id=${item.id} at $triggerMillis")
        } catch (se: SecurityException) {
            Log.w(TAG, "setAlarmClock denied, falling back: ${se.message}")
            scheduleExactWithFallback(am, triggerMillis, operationPendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm id=${item.id}", e)
            scheduleExactWithFallback(am, triggerMillis, operationPendingIntent)
        }
    }

    /**
     * 正確なアラーム権限の有無を確認しつつ、必要に応じて inexact へ安全にフォールバックする。
     */
    fun scheduleExactWithFallback(am: AlarmManager, triggerMillis: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to setAndAllowWhileIdle: ${se.message}")
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            } catch (e: Exception) {
                Log.e(TAG, "Fallback inexact schedule failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback alarm schedule failed", e)
        }
    }

    fun cancelAlarm(context: Context, alarmId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * 端末再起動後・アプリ更新後などに有効なアラームをすべて再登録する。
     */
    fun rescheduleAllEnabledAlarms(context: Context) {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(CuraConstants.KEY_ALARM_LIST, null) ?: return
        try {
            val items = json.decodeFromString<List<AlarmItem>>(jsonStr)
            items.filter { it.isEnabled }.forEach { item ->
                val audioFile = File(context.filesDir, "${item.id}_alarm.wav")
                // 音声キャッシュが削除済みの場合でもAlarmService側でデフォルト音にフォールバックして鳴動できる
                scheduleAlarm(context, item, audioFile.absolutePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule alarms", e)
        }
    }
}
