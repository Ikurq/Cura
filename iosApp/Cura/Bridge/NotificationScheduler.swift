import Foundation
import Shared
import Shared
import UserNotifications

/// 共通ロジックが組んだ通知プランを、実際の `UNNotificationRequest` にする。
///
/// iOS はアラーム時刻に自前のコードを走らせられないため、Android 版のように
/// 鳴動時に音声を組み立てることができない。代わりに、アラームを保存・更新した
/// 時点で音声を合成しておき、その WAV を通知音として添える。
///
/// **通知音は30秒までしか鳴らない**という iOS の制約があるので、タスクを読み上げる
/// 長いアラームは通知音の中では途中で切れる。通知をタップして開いたときに、
/// `AlarmAlertView` が同じ音声を最後まで再生する。
@MainActor
final class NotificationScheduler: ObservableObject {

    @Published private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined

    private let center = UNUserNotificationCenter.current()

    func refreshAuthorizationStatus() async {
        authorizationStatus = await center.notificationSettings().authorizationStatus
    }

    @discardableResult
    func requestAuthorization() async -> Bool {
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        await refreshAuthorizationStatus()
        return granted
    }

    /// iOS が保持できる保留中通知の数。これを超えると以降の登録は黙って捨てられる。
    private static let pendingLimit = 64

    /// 予約一覧を丸ごと置き換える。
    ///
    /// 上限を超えるぶんは捨てるしかないので、何を残すかをここで決める。
    /// アラームを優先し、同じ種別なら発火が早いものを残す。
    /// 捨てた件数は [droppedPlanCount] で分かるようにしておく。
    func reschedule(_ plans: [PlannedNotification]) async {
        center.removeAllPendingNotificationRequests()

        let ordered = plans.sorted { lhs, rhs in
            let lhsIsAlarm = lhs.kind == PlannedNotification.Kind.alarm
            let rhsIsAlarm = rhs.kind == PlannedNotification.Kind.alarm
            if lhsIsAlarm != rhsIsAlarm { return lhsIsAlarm }
            return lhs.triggerMillis < rhs.triggerMillis
        }

        let accepted = ordered.prefix(Self.pendingLimit)
        droppedPlanCount = ordered.count - accepted.count

        var failed = 0
        for plan in accepted where await !schedule(plan) {
            failed += 1
        }
        failedPlanCount = failed
    }

    /// 上限に当たって予約できなかった件数。
    @Published private(set) var droppedPlanCount = 0

    /// 登録そのものが失敗した件数。
    @Published private(set) var failedPlanCount = 0

    @discardableResult
    private func schedule(_ plan: PlannedNotification) async -> Bool {
        let fireDate = Date(timeIntervalSince1970: Double(plan.triggerMillis) / 1000)
        // 繰り返しは次回の発火時刻から曜日と時刻だけを取るので、過去でも構わない
        guard plan.repeatWeekday != nil || fireDate > Date() else { return true }

        let content = UNMutableNotificationContent()
        content.title = plan.title
        content.body = plan.body
        var info: [String: Any] = ["planId": plan.id, "kind": plan.kind.name]
        // plan.id は繰り返しぶんで接尾辞が付くので、引き当てには sourceId を使う
        if let sourceId = plan.sourceId { info["sourceId"] = sourceId }
        content.userInfo = info

        if let soundFileName = plan.soundFileName {
            // Library/Sounds に置いた WAV は名前だけで参照できる
            content.sound = UNNotificationSound(named: UNNotificationSoundName(soundFileName))
        } else {
            content.sound = .default
        }
        if plan.kind == .alarm {
            content.interruptionLevel = .timeSensitive
        }

        let trigger: UNCalendarNotificationTrigger
        if let weekday = plan.repeatWeekday?.intValue {
            // OS 側で毎週繰り返させる。アプリを開かなくても鳴り続ける。
            // Kotlin 側は 1=日 … 7=土 で、DateComponents.weekday と同じ並び。
            var components = Calendar.current.dateComponents([.hour, .minute], from: fireDate)
            components.weekday = weekday
            trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
        } else {
            let components = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute, .second],
                from: fireDate
            )
            trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        }
        let request = UNNotificationRequest(identifier: plan.id, content: content, trigger: trigger)

        do {
            try await center.add(request)
            return true
        } catch {
            return false
        }
    }

    /// 予約されている通知(デバッグ・設定画面の確認用)。
    func pending() async -> [UNNotificationRequest] {
        await center.pendingNotificationRequests()
    }

    /// 既に配信されたアラーム通知の、元アラームID。
    ///
    /// iOS はアラーム時刻にコードを走らせられないので、「鳴ったかどうか」は
    /// あとから配信済み通知を見て知るしかない。
    func deliveredAlarmIds() async -> [String] {
        await center.deliveredNotifications().compactMap { notification in
            let info = notification.request.content.userInfo
            guard info["kind"] as? String == PlannedNotification.Kind.alarm.name else { return nil }
            return info["sourceId"] as? String
        }
    }

    func clearDelivered() {
        center.removeAllDeliveredNotifications()
    }
}
