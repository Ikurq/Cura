import Shared
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    /// 親（MoreView）の NavigationStack に push される前提。ここで NavigationStack を
    /// 重ねると戻るボタンが2つ並ぶ。
    var body: some View {
        List {
            Section("設定") {
                NavigationLink("権限設定") { PermissionsView() }
                NavigationLink("通知設定") { NotificationSettingsView() }
                NavigationLink("カレンダー管理") { CalendarSettingsView() }
                NavigationLink("アラーム詳細設定") { AlarmAdvancedView() }
                NavigationLink("音声・ストレージ") { VoiceModelsView() }
                NavigationLink("HUDカスタマイズ") { HudSettingsView() }
            }
            Section {
                NavigationLink("Credits") { CreditsView() }
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("設定")
    }
}

// MARK: - 権限

struct PermissionsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var calendarGranted = false
    @State private var notificationStatus = "未確認"

    var body: some View {
        List {
            Section("カレンダー") {
                HStack {
                    VStack(alignment: .leading) {
                        Text("カレンダーの読み取り")
                        Text("時間割の自動同期に必要です")
                            .font(.caption).foregroundStyle(Theme.textTertiary)
                    }
                    Spacer()
                    if calendarGranted {
                        Text("許可済み").foregroundStyle(Theme.hpBar)
                    } else {
                        Button("許可") {
                            Task { calendarGranted = await model.calendar.requestAccess() }
                        }
                    }
                }
            }

            Section("通知") {
                HStack {
                    VStack(alignment: .leading) {
                        Text("通知の許可")
                        Text("アラームと各種リマインドに必要です")
                            .font(.caption).foregroundStyle(Theme.textTertiary)
                    }
                    Spacer()
                    Text(notificationStatus).foregroundStyle(Theme.textSecondary)
                }
                Button("通知を許可する") {
                    Task {
                        await model.notifications.requestAuthorization()
                        await refresh()
                    }
                }
                Button("システム設定を開く") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }

            Section {
                Text("⚠️ マナーモード（消音スイッチ）中は、アラームの通知音が鳴りません。iOS では純正の時計アプリだけが消音を無視でき、他のアプリは同じことができません。確実に起きたい日は消音を解除してください。\n\n通知をタップして開いたあとの読み上げは、消音中でも鳴ります。")
                    .font(.caption)
                    .foregroundStyle(Theme.warning)

                Text("iOS ではアプリがアラーム時刻にコードを実行できないため、音声はアラームを保存した時点で合成し、通知音として再生します。通知音は30秒までという制約があるので、タスクの読み上げが長い場合は通知をタップして開くと最後まで再生されます。")
                    .font(.caption)
                    .foregroundStyle(Theme.textTertiary)
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("権限設定")
        .task { await refresh() }
    }

    private func refresh() async {
        calendarGranted = model.calendar.hasAccess()
        await model.notifications.refreshAuthorizationStatus()
        notificationStatus = switch model.notifications.authorizationStatus {
        case .authorized, .provisional, .ephemeral: "許可済み"
        case .denied: "拒否"
        default: "未設定"
        }
    }
}

// MARK: - 通知

struct NotificationSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var mandatory = true
    @State private var task = true
    @State private var event = true

    var body: some View {
        List {
            Toggle(isOn: $mandatory) {
                VStack(alignment: .leading) {
                    Text("絶対起きるアラームのリマインド")
                    Text("0時にアラームが未設定なら通知")
                        .font(.caption).foregroundStyle(Theme.textTertiary)
                }
            }
            Toggle(isOn: $task) {
                VStack(alignment: .leading) {
                    Text("タスクの締切通知")
                    Text("締切の1時間前に通知します")
                        .font(.caption).foregroundStyle(Theme.textTertiary)
                }
            }
            Toggle(isOn: $event) {
                VStack(alignment: .leading) {
                    Text("予定の直前通知")
                    Text("予定の10分前に通知します")
                        .font(.caption).foregroundStyle(Theme.textTertiary)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("通知設定")
        .onAppear {
            mandatory = model.cura.settings.mandatoryReminder
            task = model.cura.settings.taskNotification
            event = model.cura.settings.eventNotification
        }
        .onDisappear {
            model.cura.settings.mandatoryReminder = mandatory
            model.cura.settings.taskNotification = task
            model.cura.settings.eventNotification = event
            Task { await model.rescheduleNotifications() }
        }
    }
}

// MARK: - カレンダー

struct CalendarSettingsView: View {
    @EnvironmentObject private var model: AppModel

    @State private var sources: [CalendarSource] = []
    @State private var deviceCalendars: [DeviceCalendarInfo] = []
    @State private var selectedIds: Set<String> = []
    @State private var syncDevice = false
    @State private var newName = ""
    @State private var newUrl = ""

    var body: some View {
        List {
            Section("外部カレンダー (iCal)") {
                ForEach(sources, id: \.url) { source in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(source.name).foregroundStyle(Theme.textPrimary)
                        Text(source.url)
                            .font(.caption).lineLimit(1)
                            .foregroundStyle(Theme.textTertiary)
                    }
                }
                .onDelete { offsets in
                    for index in offsets { model.cura.schedules.deleteCalendarSource(url: sources[index].url) }
                    sources = model.cura.schedules.calendarSources()
                }

                TextField("カレンダー名", text: $newName)
                TextField("https://...", text: $newUrl)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("追加する") {
                    guard !newName.isEmpty, !newUrl.isEmpty else { return }
                    model.cura.schedules.addCalendarSource(source: CalendarSource(name: newName, url: newUrl))
                    sources = model.cura.schedules.calendarSources()
                    newName = ""
                    newUrl = ""
                }
                .disabled(newName.isEmpty || newUrl.isEmpty)

                Button("今すぐ同期") { Task { await model.syncCalendars() } }
            }

            Section("端末のカレンダー") {
                Toggle("端末のカレンダーと同期する", isOn: $syncDevice)
                    .onChange(of: syncDevice) { value in
                        model.cura.settings.syncDeviceCalendar = value
                        if value { Task { _ = await model.calendar.requestAccess(); reload() } }
                        model.refreshAll()
                    }

                if syncDevice {
                    if deviceCalendars.isEmpty {
                        Text("カレンダーが見つかりません(権限を確認してください)")
                            .font(.caption).foregroundStyle(Theme.textTertiary)
                    }
                    ForEach(deviceCalendars, id: \.id) { calendar in
                        Button {
                            if selectedIds.contains(calendar.id) {
                                selectedIds.remove(calendar.id)
                            } else {
                                selectedIds.insert(calendar.id)
                            }
                            model.cura.settings.selectedCalendarIds = Array(selectedIds)
                            model.refreshAll()
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(calendar.name).foregroundStyle(Theme.textPrimary)
                                    Text(calendar.account)
                                        .font(.caption).foregroundStyle(Theme.textTertiary)
                                }
                                Spacer()
                                if selectedIds.contains(calendar.id) {
                                    Image(systemName: "checkmark").foregroundStyle(Theme.cyan)
                                }
                            }
                        }
                    }
                    Text("1つも選ばない場合は、すべてのカレンダーが対象になります。")
                        .font(.caption).foregroundStyle(Theme.textTertiary)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("カレンダー管理")
        .onAppear { reload() }
    }

    private func reload() {
        sources = model.cura.schedules.calendarSources()
        syncDevice = model.cura.settings.syncDeviceCalendar
        selectedIds = Set(model.cura.settings.selectedCalendarIds)
        deviceCalendars = model.calendar.calendars()
    }
}

// MARK: - アラーム詳細

struct AlarmAdvancedView: View {
    @EnvironmentObject private var model: AppModel
    @State private var skipHolidays = false
    @State private var vacationMode = false

    var body: some View {
        List {
            Toggle(isOn: $skipHolidays) {
                VStack(alignment: .leading) {
                    Text("祝日はアラームを鳴らさない")
                    Text("日本の祝日に合わせて自動でスキップします")
                        .font(.caption).foregroundStyle(Theme.textTertiary)
                }
            }
            Toggle(isOn: $vacationMode) {
                VStack(alignment: .leading) {
                    Text("長期休暇モード (一括停止)")
                    Text("ONの間、全てのアラームを一時停止します")
                        .font(.caption).foregroundStyle(Theme.textTertiary)
                }
            }
            Section {
                Text("祝日判定は固定祝日・ハッピーマンデー・春分秋分・振替休日までの簡易版です。国民の休日は見ていません。")
                    .font(.caption).foregroundStyle(Theme.textTertiary)
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("アラーム詳細設定")
        .onAppear {
            skipHolidays = model.cura.settings.skipHolidays
            vacationMode = model.cura.settings.vacationMode
        }
        .onDisappear {
            model.cura.settings.skipHolidays = skipHolidays
            model.cura.settings.vacationMode = vacationMode
            Task { await model.rescheduleNotifications() }
        }
    }
}

// MARK: - HUD

struct HudSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var userName = ""
    @State private var showPlayerLevel = true
    @State private var showCharacterLevel = true

    var body: some View {
        List {
            Section("表示名") {
                TextField("ユーザー名", text: $userName)
            }
            Section("表示") {
                Toggle("プレイヤーレベルを表示", isOn: $showPlayerLevel)
                Toggle("キュラのレベルを表示", isOn: $showCharacterLevel)
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("HUDカスタマイズ")
        .onAppear {
            userName = model.cura.settings.userName
            showPlayerLevel = model.cura.settings.showPlayerLevel
            showCharacterLevel = model.cura.settings.showCharacterLevel
        }
        .onDisappear {
            model.cura.settings.userName = userName
            model.cura.settings.showPlayerLevel = showPlayerLevel
            model.cura.settings.showCharacterLevel = showCharacterLevel
            model.refreshAll()
        }
    }
}

// MARK: - Credits

struct CreditsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List {
            Section("音声合成") {
                Text("VOICEVOX (https://voicevox.hiroshiba.jp/)")
                    .font(.footnote)
                Text("音声は端末内で合成しています。")
                    .font(.caption).foregroundStyle(Theme.textTertiary)
            }
            if !model.voice.creditTexts.isEmpty {
                Section("クレジット表記") {
                    ForEach(model.voice.creditTexts, id: \.self) { credit in
                        Text(credit).font(.footnote)
                    }
                    Text("生成した音声を公開・配布する場合は、上記の表記が必要です。")
                        .font(.caption).foregroundStyle(Theme.textTertiary)
                }
            }
            Section("キャラクター") {
                Text("Cura（キュラ / CU-RA v2.0）\nCuration & Rehabilitation Assistant")
                    .font(.footnote)
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("Credits")
    }
}
