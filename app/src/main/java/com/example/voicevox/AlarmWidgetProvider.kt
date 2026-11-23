package com.example.voicevox

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.serialization.json.Json
import java.util.*

class AlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun triggerUpdate(context: Context) {
            val intent = Intent(context, AlarmWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, AlarmWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_alarm)
            
            // 1. 次回のアラーム情報を取得（繰り返し曜日を考慮）
            val prefs = context.getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
            val alarmJson = prefs.getString(CuraConstants.KEY_ALARM_LIST, null)
            
            var nextAlarmStr = "--:--"
            var speakerStr = "NOT SET"
            
            if (alarmJson != null) {
                try {
                    val allAlarms = Json.decodeFromString<List<AlarmItem>>(alarmJson)
                    val next = AlarmScheduler.findNextActiveAlarm(allAlarms)
                    if (next != null) {
                        nextAlarmStr = String.format(Locale.getDefault(), "%02d:%02d", next.hour, next.minute)
                        speakerStr = "[${next.speakerName}]"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            views.setTextViewText(R.id.widgetAlarmTime, nextAlarmStr)
            views.setTextViewText(R.id.widgetAlarmSpeaker, speakerStr)

            // 2. タップでアプリを開く
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetAlarmRoot, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
