import Combine
import Foundation
import Shared
import SwiftUI

/// アプリ全体の状態。共通ロジック(`Cura`)への入り口と、プラットフォーム側の部品を持つ。
@MainActor
final class AppModel: ObservableObject {

    let cura: Cura
    let calendar = EventKitCalendar()
    let voice = VoiceService()
    let notifications = NotificationScheduler()
    let audio = AlarmAudioPlayer()

    // 画面が参照する状態。共通ロジックは同期的に読めるので、
    // 変更のたびにここへ読み直す。
    @Published private(set) var alarms: [AlarmItem] = []
    @Published private(set) var tasks: [TaskItem] = []
    @Published private(set) var todayItems: [ScheduleItem] = []
    @Published private(set) var attendance: [SubjectStats] = []
    @Published private(set) var playerLevel: LevelInfo = PlayerRepository.companion.levelOf(exp: 0)
    @Published private(set) var characterLevel: LevelInfo = PlayerRepository.companion.levelOf(exp: 0)

    /// 吹き出しに出しているセリフ。
    @Published var dialogue: Dialogue?
    @Published var toast: String?

    /// 通知から開かれたアラーム。全画面で読み上げる。
    @Published var presentedAlarmId: String?

    private var icsSync: IcsSync { IcsSync(cura: cura) }

    init() {
        cura = Cura(
            storeFactory: UserDefaultsStoreFactory(defaults: .standard),
            deviceCalendar: calendar
        )
        refreshAll()
    }

    // MARK: - 読み直し

    func refreshAll() {
        alarms = cura.alarms.all()
        tasks = cura.tasks.all(nowMillis: nowMillis())
        todayItems = cura.schedule.itemsOn(date: CuraTime.shared.today())
        attendance = cura.attendance.summary()
        playerLevel = cura.player.playerLevel
        characterLevel = cura.player.characterLevel
    }

    func nowMillis() -> Int64 { CuraTime.shared.nowMillis() }

    // MARK: - アラーム

    /// アラームを保存し、音声を合成して通知を張り直す。
    func saveAlarm(_ alarm: AlarmItem) async {
        // 同じ時刻・同じ話者の既存アラームは置き換える(Android 版と同じ)
        if let duplicate = cura.alarms.duplicateOf(
            hour: alarm.hour, minute: alarm.minute, speakerId: alarm.speakerId
        ), duplicate.id != alarm.id {
            cura.alarms.delete(id: duplicate.id)
        }

        cura.alarms.upsert(item: alarm)
        refreshAll()
        await synthesizeAndReschedule(for: alarm)
    }

    func deleteAlarm(_ alarm: AlarmItem) async {
        cura.alarms.delete(id: alarm.id)
        refreshAll()
        await rescheduleNotifications()
    }

    func setAlarmEnabled(_ alarm: AlarmItem, enabled: Bool) async {
        cura.alarms.upsert(item: alarm.doCopy(
            id: alarm.id,
            hour: alarm.hour,
            minute: alarm.minute,
            message: alarm.message,
            speakerId: alarm.speakerId,
            speakerName: alarm.speakerName,
            isEnabled: enabled,
            readTasks: alarm.readTasks,
            vibrate: alarm.vibrate,
            repeatDays: alarm.repeatDays
        ))
        refreshAll()
        await rescheduleNotifications()
    }

    /// アラームの読み上げ音声を作ってから通知を張り直す。
    ///
    /// 合成そのものは [rescheduleNotifications] が必要に応じて行う。ここでは
    /// 保存直後に失敗を知らせたいので、結果を見てメッセージを出すだけ。
    private func synthesizeAndReschedule(for alarm: AlarmItem) async {
        let text = cura.alarmPlanner.speechText(alarm: alarm, forMillis: alarm.nextTriggerMillis(fromMillis: nowMillis()))
        let fileName = alarmSoundFileName(alarmId: alarm.id, text: text, styleId: alarm.speakerId)

        switch await voice.synthesize(text: text, styleId: alarm.speakerId, fileName: fileName) {
        case .success:
            break
        case .modelMissing(let characterName):
            toast = "\(characterName) の音声モデルが未取得です。設定から取得してください。"
        case .failure(let message):
            toast = "音声の生成に失敗しました: \(message)"
        }
        await rescheduleNotifications()
    }

    /// 全アラーム + リマインダーの通知を張り直す。
    func rescheduleNotifications() async {
        // 鳴り終わった単発アラームは、先に片付けておかないと翌日また予約されてしまう
        await retireFiredOneShotAlarms()

        var plans: [PlannedNotification] = []

        for alarm in cura.alarms.all() where alarm.isEnabled {
            // 通知音には短い方(時刻 + セリフ)を使う。iOS の通知音は30秒を超えると
            // 途中で切れるのではなく、既定音に落ちてしまうため。
            // タスクを含む全文はアプリを開いたときに AlarmAlertView が鳴らす。
            let text = cura.alarmPlanner.shortSpeechText(alarm: alarm)
            let fileName = alarmSoundFileName(alarmId: alarm.id, text: text, styleId: alarm.speakerId)
            let soundURL = VoiceService.soundsDirectory.appendingPathComponent(fileName)

            if !FileManager.default.fileExists(atPath: soundURL.path) {
                _ = await voice.synthesize(text: text, styleId: alarm.speakerId, fileName: fileName)
            }
            // 長すぎる音声は通知に載せても鳴らないので、既定音に任せる
            let soundExists = FileManager.default.fileExists(atPath: soundURL.path)
                && AudioDuration.seconds(of: soundURL).map { $0 < 30 } ?? false
            // 繰り返しアラームは複数返る(OS の週次繰り返し、または祝日を避けた個別予約)
            plans += cura.alarmPlanner.planAlarms(
                alarm: alarm,
                soundFileName: soundExists ? fileName : nil,
                fromMillis: nowMillis(),
                occurrences: 8
            )
        }

        // アプリを開かないと予約を張り直せないので、リマインダー類は先の数日ぶんまで
        // まとめて入れておく。
        plans += cura.alarmPlanner.planReminders(fromMillis: nowMillis(), lookaheadDays: 7)
        plans += cura.alarmPlanner.planMandatoryReminders(fromMillis: nowMillis(), lookaheadDays: 7)

        await notifications.reschedule(plans)
    }

    /// 配信済みのアラーム通知を見て、鳴り終わった単発アラームを片付ける。
    ///
    /// Android は鳴動画面で後始末できるが、iOS はアラーム時刻にコードを
    /// 走らせられないので、アプリを開いたときにまとめて処理する。
    private func retireFiredOneShotAlarms() async {
        let firedIds = await notifications.deliveredAlarmIds()
        guard !firedIds.isEmpty else { return }

        for id in Set(firedIds) {
            cura.retireAlarmAfterFiring(id: id)
        }
        notifications.clearDelivered()
        refreshAll()
    }

    // MARK: - タスク

    func saveTask(_ task: TaskItem) async {
        cura.tasks.upsert(item: task)
        refreshAll()
        await rescheduleNotifications()
    }

    func deleteTask(_ task: TaskItem) async {
        cura.tasks.delete(id: task.id)
        refreshAll()
        await rescheduleNotifications()
    }

    /// 複数タスクをまとめて完了にする。経験値は共通ロジックが加算する。
    func completeTasks(_ ids: [String]) async {
        let before = cura.player.playerExp
        cura.completeTasks(ids: ids)
        let gained = cura.player.playerExp - before
        refreshAll()
        if gained > 0 { toast = "+\(gained) EXP" }
        await rescheduleNotifications()
    }

    // MARK: - 予定

    func saveEvent(_ event: ScheduleEvent) async {
        cura.schedules.upsertCustomEvent(event: event)
        refreshAll()
        await rescheduleNotifications()
    }

    func deleteEvent(id: String) async {
        cura.schedules.deleteCustomEvent(id: id)
        refreshAll()
        await rescheduleNotifications()
    }

    func record(_ status: AttendanceStatus, subject: String, startMillis: Int64) {
        cura.attendance.record(subject: subject, startMillis: startMillis, status: status)
        refreshAll()
    }

    /// 購読中の iCal を取り込み直す。
    func syncCalendars() async {
        let result = await icsSync.refresh()
        refreshAll()
        await rescheduleNotifications()

        if result.failed.isEmpty {
            toast = "\(result.imported) 件の予定を取り込みました"
        } else {
            toast = "一部の取得に失敗しました: \(result.failed.joined(separator: "、"))"
        }
    }

    // MARK: - キャラクター

    /// キャラクターをタップしたときの反応。
    func tapCharacter(consecutiveTaps: Int) {
        dialogue = cura.character.onTap(
            consecutiveTaps: Int32(consecutiveTaps),
            batteryPercent: Int32(batteryPercent()),
            nowMillis: nowMillis()
        )
        refreshAll()
    }

    func showIdleDialogue() {
        guard dialogue == nil else { return }
        dialogue = cura.character.idleDialogue()
    }

    /// レベルの節目で1回だけ流れるストーリー。無ければ何もしない。
    func showPendingStoryIfAny() {
        if let story = cura.character.pendingStory() {
            dialogue = story
        }
    }

    var costume: Costume { cura.character.costume(nowMillis: nowMillis()) }

    /// ホーム下部を流れる SYS_LOG。内容は固定なので毎回引いてよい。
    var systemLogLines: [String] { cura.character.systemLogLines() }

    private func batteryPercent() -> Int {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        // シミュレータなどで取れないときは満充電扱いにして、電池警告を出さない
        return level < 0 ? 100 : Int(level * 100)
    }
}
