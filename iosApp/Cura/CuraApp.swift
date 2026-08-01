import Shared
import SwiftUI
import UserNotifications

@main
struct CuraApp: App {
    @StateObject private var model = AppModel()
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .task {
                    appDelegate.model = model
                    await model.notifications.refreshAuthorizationStatus()
                    // 起動のたびに予約を組み直す。日付が変わればリマインダーの対象も変わる。
                    await model.rescheduleNotifications()
                    model.showPendingStoryIfAny()
                }
        }
    }
}

/// 通知のタップを受けて、該当アラームの全画面を出す。
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    /// 通知タップでコールドスタートした場合、SwiftUI の `.task` より先に
    /// `didReceive` が来ることがある。その時点ではモデルがまだ無いので、
    /// アラームIDを覚えておいて接続時に流す。
    private var pendingAlarmId: String?

    weak var model: AppModel? {
        didSet {
            guard let model, let alarmId = pendingAlarmId else { return }
            pendingAlarmId = nil
            model.presentedAlarmId = alarmId
        }
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// 前面にいるときも鳴らす。目覚ましなので黙らせない。
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        guard info["kind"] as? String == PlannedNotification.Kind.alarm.name,
              let alarmId = info["sourceId"] as? String
        else { return }

        await MainActor.run {
            if let model {
                model.presentedAlarmId = alarmId
            } else {
                pendingAlarmId = alarmId
            }
        }
    }
}
