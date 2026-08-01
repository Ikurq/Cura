package com.example.voicevox.core.alarm

import com.example.voicevox.core.calendar.JapaneseHolidays
import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.model.AlarmItem
import com.example.voicevox.core.repository.AlarmRepository
import com.example.voicevox.core.repository.SettingsRepository
import com.example.voicevox.core.repository.TaskRepository
import com.example.voicevox.core.schedule.ScheduleService

/** 予約する通知1件。プラットフォーム側がこれを実際の通知に変換する。 */
data class PlannedNotification(
    /** 再予約時に上書きできるよう安定した識別子にする。 */
    val id: String,
    val triggerMillis: Long,
    val title: String,
    val body: String,
    /** 鳴らす音声ファイル名。null なら既定の通知音。 */
    val soundFileName: String? = null,
    val kind: Kind,
    /**
     * OS 側で繰り返させる曜日(1=日 … 7=土)。null なら1回きり。
     *
     * これが入っているものは、アプリが起動されなくても鳴り続ける。
     * ただし OS に任せる以上、祝日だけ飛ばすといった判定はできないので、
     * [AlarmPlanner] は祝日スキップが無効なときにだけこれを使う。
     */
    val repeatWeekday: Int? = null,
    /**
     * この通知の元になったデータのID(アラームID・タスクIDなど)。
     *
     * [id] は繰り返しぶんを別々に予約するため接尾辞が付くので、
     * 通知をタップしたときの引き当てにはこちらを使う。
     */
    val sourceId: String? = null,
) {
    enum class Kind { ALARM, TASK_DEADLINE, TASK_OVERDUE, EVENT_REMINDER, MANDATORY_REMINDER }
}

/**
 * 「いつ何を鳴らすか」を決める。
 *
 * iOS はアラーム時刻に自前のコードを走らせられないので、Android 版のように
 * 鳴動時に音声を組み立てることができない。そのため
 * 「事前に音声を作って通知に添える」形にしてあり、プランを組む部分だけを
 * ここに置いてプラットフォームから切り離している。
 */
class AlarmPlanner(
    private val alarmRepository: AlarmRepository,
    private val taskRepository: TaskRepository,
    private val scheduleService: ScheduleService,
    private val settings: SettingsRepository,
) {

    /**
     * アラームを鳴らすべき日か。
     *
     * 長期休暇モード中は全部止める。祝日スキップが有効なら祝日も鳴らさない。
     * Android 版は設定項目だけあって判定が繋がっていなかったので、ここで実装している。
     */
    fun shouldRing(triggerMillis: Long): Boolean {
        if (settings.vacationMode) return false
        if (settings.skipHolidays && JapaneseHolidays.isHoliday(triggerMillis)) return false
        return true
    }

    /**
     * アラーム1件分の通知。鳴らさない日なら、その次に鳴る日まで進める。
     *
     * @param soundFileName 事前に合成しておいた音声のファイル名。
     */
    fun planAlarm(alarm: AlarmItem, soundFileName: String?, fromMillis: Long = CuraTime.nowMillis()): PlannedNotification? =
        planAlarms(alarm, soundFileName, fromMillis).minByOrNull { it.triggerMillis }

    /**
     * アラーム1件を鳴らすための通知プラン。繰り返しアラームは複数返る。
     *
     * 繰り返しアラームを1件だけ予約すると、初回が鳴ったあとアプリを開くまで
     * 二度と鳴らなくなる(iOS はアラーム時刻に自前のコードを走らせられないため、
     * 鳴った直後に次を仕込むことができない)。そこで2通りを使い分ける:
     *
     * - **祝日スキップが無効** … 曜日ごとに [PlannedNotification.repeatWeekday] を立てて
     *   OS の繰り返しに任せる。アプリを開かなくても鳴り続ける。
     * - **祝日スキップが有効** … 祝日を飛ばす判定はアプリ側にしかないので、
     *   OS の繰り返しは使えない。先の [occurrences] 回ぶんを個別に予約する。
     *   予約しただけ先までしか鳴らないので、アプリを開いたときに毎回張り直すこと。
     */
    fun planAlarms(
        alarm: AlarmItem,
        soundFileName: String?,
        fromMillis: Long = CuraTime.nowMillis(),
        occurrences: Int = DEFAULT_OCCURRENCES,
    ): List<PlannedNotification> {
        if (!alarm.isEnabled) return emptyList()
        if (settings.vacationMode) return emptyList()

        fun notification(id: String, trigger: Long, weekday: Int?) = PlannedNotification(
            id = id,
            triggerMillis = trigger,
            title = "Cura",
            body = alarm.message,
            soundFileName = soundFileName,
            kind = PlannedNotification.Kind.ALARM,
            repeatWeekday = weekday,
            sourceId = alarm.id,
        )

        // 単発。祝日に当たるならそのまま鳴らさない。
        if (alarm.repeatDays.isEmpty()) {
            val trigger = alarm.nextTriggerMillis(fromMillis)
            return if (shouldRing(trigger)) listOf(notification("alarm-${alarm.id}", trigger, null)) else emptyList()
        }

        // 祝日を見なくてよいなら OS の繰り返しに委ねる。
        // 単発の予約しか置けない Android から planAlarm で1件だけ取る場合に
        // 直近の曜日が選ばれるよう、発火順に並べて返す。
        if (!settings.skipHolidays) {
            return alarm.repeatDays.distinct().map { weekday ->
                notification(
                    id = "alarm-${alarm.id}-w$weekday",
                    trigger = CuraTime.nextOccurrence(alarm.hour, alarm.minute, listOf(weekday), fromMillis),
                    weekday = weekday,
                )
            }.sortedBy { it.triggerMillis }
        }

        // 祝日スキップ有効。鳴る日だけを先の回数ぶん拾う。
        val plans = mutableListOf<PlannedNotification>()
        var cursor = fromMillis
        var guard = 0
        while (plans.size < occurrences && guard < MAX_LOOKAHEAD) {
            guard++
            val trigger = alarm.nextTriggerMillis(cursor)
            cursor = trigger
            if (!shouldRing(trigger)) continue
            plans += notification("alarm-${alarm.id}-${plans.size}", trigger, null)
        }
        return plans
    }

    /**
     * リマインダー類。Android 版 AlarmReceiver.scheduleDailyNotifications と同じ規則。
     *
     * - タスク: 締切の1時間前(当日ぶんのみ)。締切超過は1時間後に再通知。
     * - 予定: 開始10分前(当日ぶんのみ)。
     */
    fun planReminders(
        fromMillis: Long = CuraTime.nowMillis(),
        lookaheadDays: Int = DEFAULT_LOOKAHEAD_DAYS,
    ): List<PlannedNotification> {
        val result = mutableListOf<PlannedNotification>()
        val horizon = fromMillis + lookaheadDays * ONE_DAY

        if (settings.taskNotification) {
            taskRepository.pending().forEach { task ->
                val beforeDeadline = task.deadlineMillis - ONE_HOUR
                if (beforeDeadline > fromMillis && beforeDeadline <= horizon) {
                    result += PlannedNotification(
                        id = "task-due-${task.id}",
                        triggerMillis = beforeDeadline,
                        title = "タスク期限1時間前",
                        body = task.title,
                        kind = PlannedNotification.Kind.TASK_DEADLINE,
                        sourceId = task.id,
                    )
                }
                if (fromMillis > task.deadlineMillis) {
                    result += PlannedNotification(
                        id = "task-overdue-${task.id}",
                        triggerMillis = fromMillis + ONE_HOUR,
                        title = "【期限超過】タスク未完了",
                        body = task.title,
                        kind = PlannedNotification.Kind.TASK_OVERDUE,
                        sourceId = task.id,
                    )
                }
            }
        }

        if (settings.eventNotification) {
            val days = (0..lookaheadDays).map { CuraTime.toLocalDate(fromMillis + it * ONE_DAY) }.distinct()
            days.flatMap { scheduleService.eventsOn(it) }.forEach { event ->
                val notifyAt = event.startTime - TEN_MINUTES
                if (notifyAt > fromMillis && notifyAt <= horizon) {
                    result += PlannedNotification(
                        id = "event-${event.summary}-${event.startTime}",
                        triggerMillis = notifyAt,
                        title = "予定10分前",
                        body = event.summary,
                        kind = PlannedNotification.Kind.EVENT_REMINDER,
                    )
                }
            }
        }

        return result.sortedBy { it.triggerMillis }
    }

    /**
     * 「絶対起きるアラーム」が当日ぶん未設定なら出すリマインド。
     *
     * 判定は Android 版と同じで、メッセージに「本日の予定である」を含む
     * 有効なアラームがあるかどうかで見ている。
     */
    fun planMandatoryReminder(atMillis: Long): PlannedNotification? {
        if (!settings.mandatoryReminder) return null

        val hasMandatory = alarmRepository.all().any { alarm ->
            alarm.isEnabled && alarm.message.contains("本日の予定である") &&
                CuraTime.isSameDay(alarm.nextTriggerMillis(atMillis), atMillis)
        }
        if (hasMandatory) return null

        return PlannedNotification(
            id = "mandatory-reminder",
            triggerMillis = atMillis,
            title = "絶対起きるアラーム未設定",
            body = "本日の重要予定に対するアラームが設定されていません。アプリを開いて設定を確認してください。",
            kind = PlannedNotification.Kind.MANDATORY_REMINDER,
        )
    }

    /**
     * 「絶対起きるアラーム」未設定リマインドを、先の日数ぶんまとめて。
     *
     * iOS はアプリを開かないと予約を張り直せないので、1日ぶんだけ入れると
     * 翌日以降が出なくなる。判定材料(その日にアラームがあるか)は日ごとに違うため、
     * OS の繰り返しには寄せられない。
     */
    fun planMandatoryReminders(
        fromMillis: Long = CuraTime.nowMillis(),
        lookaheadDays: Int = DEFAULT_LOOKAHEAD_DAYS,
    ): List<PlannedNotification> {
        if (!settings.mandatoryReminder) return emptyList()

        return (0..lookaheadDays).mapNotNull { offset ->
            val date = CuraTime.toLocalDate(fromMillis + offset * ONE_DAY)
            val midnight = CuraTime.startOfDayMillis(date)
            if (midnight <= fromMillis) return@mapNotNull null
            planMandatoryReminder(midnight)?.copy(id = "mandatory-reminder-$offset")
        }
    }

    /**
     * 通知音に載せる短い読み上げ文。
     *
     * iOS の通知音は30秒を超えると**再生されず既定音に落ちる**(途中で切れるのではない)。
     * タスクを読み上げる設定だと簡単に超えるので、通知にはこちらの短い方を使い、
     * 全文はアプリを開いたときに再生する。
     */
    fun shortSpeechText(alarm: AlarmItem): String =
        "${CuraTime.speakHourMinute(alarm.hour, alarm.minute)}を過ぎました。${alarm.message}"

    /**
     * アラームで読み上げる本文を組み立てる。
     *
     * 「H時M分を過ぎました。<セリフ>」に、タスク読み上げが有効なら当日のタスクを足す。
     * Android 版の startAlarmGeneration と同じ文面。
     */
    fun speechText(alarm: AlarmItem, forMillis: Long = alarm.nextTriggerMillis()): String {
        val head = "${CuraTime.speakHourMinute(alarm.hour, alarm.minute)}を過ぎました。${alarm.message}"
        if (!alarm.readTasks) return head

        val tasks = scheduleService.taskTitlesFor(forMillis)
        if (tasks.isEmpty()) return head

        return head + "。本日のタスクは、" + tasks.joinToString("") { "$it、" } + "です。"
    }

    /** 起床時の読み上げ(予定 + タスク)。Android 版 AlarmAlertActivity の文面。 */
    fun morningBriefingText(nowMillis: Long = CuraTime.nowMillis()): String? {
        val events = scheduleService.eventsToday().filter { it.startTime >= nowMillis }
        val tasks = scheduleService.taskTitlesFor(nowMillis)
        if (events.isEmpty() && tasks.isEmpty()) return null

        val builder = StringBuilder("おはようございます。ただいま、")
        val now = CuraTime.toLocalDateTime(nowMillis)
        builder.append(CuraTime.speakHourMinute(now.hour, now.minute)).append("です。")

        if (events.isNotEmpty()) {
            builder.append("今日の予定は、")
            events.forEach {
                val time = CuraTime.toLocalDateTime(it.startTime)
                builder.append("${CuraTime.speakHourMinute(time.hour, time.minute)}から${it.summary}、")
            }
            builder.append("です。")
        }
        if (tasks.isNotEmpty()) {
            builder.append("今日のタスクは、")
            tasks.forEach { builder.append("$it、") }
            builder.append("があります。")
        }
        builder.append("今日も一日、元気に頑張りましょう！")
        return builder.toString()
    }

    private companion object {
        const val ONE_HOUR = 60L * 60L * 1000L
        const val TEN_MINUTES = 10L * 60L * 1000L
        /** 祝日スキップ有効時に、先どれだけの回数を予約しておくか。 */
        const val DEFAULT_OCCURRENCES = 8

        /** 予約先を探す試行上限。全部祝日でも無限ループしないための歯止め。 */
        const val MAX_LOOKAHEAD = 60

        /** リマインダーを何日先まで予約しておくか。 */
        const val DEFAULT_LOOKAHEAD_DAYS = 7

        const val ONE_DAY = 24L * 60L * 60L * 1000L
    }
}
