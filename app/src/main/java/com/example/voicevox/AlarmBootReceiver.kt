package com.example.voicevox

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 端末再起動後やアプリアップデート後、正確なアラーム権限変更時に
 * 保存済みの有効なアラームを再登録するレシーバー。
 */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            AlarmScheduler.rescheduleAllEnabledAlarms(context)
        }
    }
}
