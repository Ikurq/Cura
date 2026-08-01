import Shared
import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }

            AlarmListView()
                .tabItem { Label("Wake", systemImage: "alarm.fill") }

            TaskListView()
                .tabItem { Label("Task", systemImage: "checklist") }

            TimetableView()
                .tabItem { Label("Quest", systemImage: "calendar") }

            AttendanceView()
                .tabItem { Label("Link", systemImage: "chart.bar.fill") }

            SettingsView()
                .tabItem { Label("Config", systemImage: "gearshape.fill") }
        }
        .tint(Theme.cyan)
        .curaBackground()
        .fullScreenCover(item: Binding(
            get: { model.presentedAlarmId.map(AlarmPresentation.init) },
            set: { model.presentedAlarmId = $0?.id }
        )) { presentation in
            AlarmAlertView(alarmId: presentation.id)
                .environmentObject(model)
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                Text(toast)
                    .font(.footnote)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Theme.surface)
                    .overlay(RoundedRectangle(cornerRadius: 2).strokeBorder(Theme.cyan.opacity(0.6)))
                    .foregroundStyle(Theme.textPrimary)
                    .padding(.bottom, 70)
                    .transition(.opacity)
                    .task(id: toast) {
                        try? await Task.sleep(for: .seconds(2.5))
                        model.toast = nil
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.toast)
    }
}

/// `fullScreenCover(item:)` 用の Identifiable ラッパー。
private struct AlarmPresentation: Identifiable {
    let id: String
}
