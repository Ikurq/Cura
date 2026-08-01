package com.example.voicevox

import android.content.Context
import com.example.voicevox.core.Cura
import com.example.voicevox.core.model.DeviceCalendarInfo
import com.example.voicevox.core.schedule.DeviceCalendarProvider
import com.example.voicevox.core.storage.SharedPreferencesStoreFactory
import java.util.Calendar

/**
 * 共通ロジック(`:shared`)への入り口。
 *
 * ICSパース・祝日判定・優先度計算・出欠集計・キャラクターのセリフといった、
 * 端末に依らないルールは全て `:shared` にある。iOS 版と同じコードが動く。
 * このファイルは Android 固有のもの(SharedPreferences / CalendarContract)を
 * つないでいるだけ。
 */
object CuraCore {

    @Volatile
    private var instance: Cura? = null

    fun of(context: Context): Cura {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: Cura(
                storeFactory = SharedPreferencesStoreFactory(context),
                deviceCalendar = AndroidDeviceCalendar(context),
            ).also { instance = it }
        }
    }
}

/** どこからでも `requireContext().cura` で共通ロジックを引けるようにする。 */
val Context.cura: Cura get() = CuraCore.of(this)

/**
 * 端末カレンダー(CalendarContract)を共通ロジックへ渡すアダプタ。
 * 実際の読み出しは既存の [DeviceCalendarLoader] のまま。
 */
private class AndroidDeviceCalendar(context: Context) : DeviceCalendarProvider {
    private val appContext = context.applicationContext

    override fun hasAccess(): Boolean = DeviceCalendarLoader.hasPermission(appContext)

    override fun calendars(): List<DeviceCalendarInfo> =
        DeviceCalendarLoader.getAllCalendars(appContext).map {
            DeviceCalendarInfo(id = it.id.toString(), name = it.name, account = it.account)
        }

    override fun events(startMillis: Long, endMillis: Long, calendarIds: List<String>): List<IcsEvent> {
        // 共通ロジックは日付を「その日の 0:00〜23:59:59」で渡してくるので、
        // 既存のローダに合わせて Calendar に直す。
        val target = Calendar.getInstance().apply { timeInMillis = startMillis }
        val selected = calendarIds.mapNotNull { it.toLongOrNull() }.toSet()
        return DeviceCalendarLoader.loadDeviceEvents(appContext, target, selected)
    }
}

// --- 旧クラス名の互換エイリアス ---
// UI 側のコードをできるだけ触らずに済むよう、モデルは共通のものへ寄せている。

typealias AlarmItem = com.example.voicevox.core.model.AlarmItem
typealias TaskItem = com.example.voicevox.core.model.TaskItem
typealias ScheduleEvent = com.example.voicevox.core.model.ScheduleEvent
typealias EventPreset = com.example.voicevox.core.model.EventPreset
typealias IcsEvent = com.example.voicevox.core.model.IcsEvent
typealias CalendarSource = com.example.voicevox.core.model.CalendarSource
typealias AttendanceStatus = com.example.voicevox.core.model.AttendanceStatus

typealias IcsParser = com.example.voicevox.core.ics.IcsParser

/** Android 版の呼び出し名を残しておくための薄いラッパ。 */
fun TaskItem.getCurrentPriority(): Int = currentPriority()

/**
 * 旧 `ScheduleLoader` の呼び出し口。中身は共通ロジックへ委譲している。
 *
 * 「手動の予定 + iCal キャッシュ + 端末カレンダー」を束ねる規則も、
 * 当日タスクの抽出も `:shared` 側にあり、iOS 版と同じコードが動く。
 */
object ScheduleLoader {

    fun loadAllEventsForToday(context: Context, targetDate: Calendar): List<IcsEvent> =
        context.cura.schedule.eventsOn(targetDate.toLocalDate())

    fun loadTasksForToday(context: Context): List<String> =
        context.cura.schedule.taskTitlesFor(System.currentTimeMillis())

    fun hasPriority5Tasks(context: Context): Boolean =
        context.cura.tasks.hasUrgentTasks()
}

/** `java.util.Calendar` を共通ロジックの `LocalDate` へ。 */
fun Calendar.toLocalDate(): kotlinx.datetime.LocalDate = kotlinx.datetime.LocalDate(
    get(Calendar.YEAR),
    get(Calendar.MONTH) + 1,
    get(Calendar.DAY_OF_MONTH),
)

/**
 * アラームの予約。AlarmManager への登録を1箇所にまとめている。
 *
 * 次に鳴る時刻の決定(繰り返し・祝日スキップ・長期休暇モード)は共通ロジックの
 * `AlarmPlanner` が持つ。ここは PendingIntent の組み立てだけを担当する。
 */
object AlarmScheduler {

    fun schedule(context: Context, item: AlarmItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pendingIntent = pendingIntent(context, item)

        val plan = context.cura.alarmPlanner.planAlarm(item, soundFileName = null)
        if (plan == null) {
            // 無効・長期休暇モード・祝日で今回は鳴らさない
            alarmManager.cancel(pendingIntent)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP, plan.triggerMillis, pendingIntent
        )
    }

    fun cancel(context: Context, item: AlarmItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.cancel(pendingIntent(context, item))
    }

    /**
     * 全アラームを登録し直す。
     *
     * 長期休暇モードや祝日スキップを切り替えると、既に登録済みの予約は
     * 古い判断のまま残る。日付が変わるたび(REFRESH_CALENDARS)にここを通すことで、
     * 休暇モードを解除したあとのアラームが復活する。
     */
    fun rescheduleAll(context: Context) {
        context.cura.alarms.all().forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, item: AlarmItem): android.app.PendingIntent {
        val intent = android.content.Intent(context, AlarmReceiver::class.java).apply {
            action = "ALARM_TRIGGER"
            putExtra("AUDIO_FILE_PATH", java.io.File(context.filesDir, "${item.id}_alarm.wav").absolutePath)
            putExtra("ALARM_ID", item.id)
            putExtra("VIBRATE", item.vibrate)
        }
        return android.app.PendingIntent.getBroadcast(
            context,
            item.id.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
