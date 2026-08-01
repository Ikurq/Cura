import EventKit
import Foundation
import Shared

/// 端末カレンダー(EventKit)から予定を読む。
///
/// Android 版の `DeviceCalendarLoader` に対応する。予定の表示名にカレンダー名を
/// `[ソース]` として載せるのも同じ。
final class EventKitCalendar: NSObject, DeviceCalendarProvider {

    private let store = EKEventStore()

    func hasAccess() -> Bool {
        if #available(iOS 17.0, *) {
            return EKEventStore.authorizationStatus(for: .event) == .fullAccess
        } else {
            return EKEventStore.authorizationStatus(for: .event) == .authorized
        }
    }

    /// 権限をリクエストする。既に許可済みなら true をそのまま返す。
    func requestAccess() async -> Bool {
        if hasAccess() { return true }
        do {
            if #available(iOS 17.0, *) {
                return try await store.requestFullAccessToEvents()
            } else {
                return try await store.requestAccess(to: .event)
            }
        } catch {
            return false
        }
    }

    func calendars() -> [DeviceCalendarInfo] {
        guard hasAccess() else { return [] }
        return store.calendars(for: .event).map { calendar in
            DeviceCalendarInfo(
                id: calendar.calendarIdentifier,
                name: calendar.title,
                account: calendar.source?.title ?? "不明"
            )
        }
    }

    func events(startMillis: Int64, endMillis: Int64, calendarIds: [String]) -> [IcsEvent] {
        guard hasAccess() else { return [] }

        let all = store.calendars(for: .event)
        // 何も選んでいなければ全部を対象にする(Android 版と同じ)
        let targets = calendarIds.isEmpty
            ? all
            : all.filter { calendarIds.contains($0.calendarIdentifier) }
        guard !targets.isEmpty else { return [] }

        let predicate = store.predicateForEvents(
            withStart: Date(timeIntervalSince1970: Double(startMillis) / 1000),
            end: Date(timeIntervalSince1970: Double(endMillis) / 1000),
            calendars: targets
        )

        return store.events(matching: predicate).map { event in
            let calendarName = event.calendar.title
            let accountName = event.calendar.source?.title ?? "不明"
            // カレンダー名とアカウント名が同じなら片方だけ出す
            let source = calendarName == accountName ? calendarName : "\(calendarName) / \(accountName)"
            let location = event.location ?? ""

            return IcsEvent(
                summary: event.title ?? "(無題)",
                startTime: Int64(event.startDate.timeIntervalSince1970 * 1000),
                endTime: Int64((event.endDate ?? event.startDate).timeIntervalSince1970 * 1000),
                location: location.isEmpty ? "[\(source)]" : "[\(source)] \(location)",
                isAttendanceTracked: false,
                attendanceStatus: "NONE"
            )
        }
    }
}
