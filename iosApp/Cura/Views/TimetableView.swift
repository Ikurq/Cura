import Shared
import SwiftUI

/// 予定。日付を選んで、その日の予定とタスクを時刻順に見る。
/// 出欠を追跡している科目はここから出席・遅刻・欠席を記録する。
struct TimetableView: View {
    @EnvironmentObject private var model: AppModel

    @State private var selectedDate = Date()
    @State private var showEditor = false
    @State private var isSyncing = false

    private var kotlinDate: Kotlinx_datetimeLocalDate {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: selectedDate)
        return Kotlinx_datetimeLocalDate(
            year: Int32(parts.year ?? 2026),
            monthNumber: Int32(parts.month ?? 1),
            dayOfMonth: Int32(parts.day ?? 1)
        )
    }

    private var items: [ScheduleItem] { model.cura.schedule.itemsOn(date: kotlinDate) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                DatePicker("", selection: $selectedDate, displayedComponents: .date)
                    .datePickerStyle(.compact)
                    .labelsHidden()
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)

                if items.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "calendar").font(.largeTitle).foregroundStyle(Theme.textTertiary)
                        Text("この日の予定はありません").foregroundStyle(Theme.textSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(items, id: \.id) { item in
                            row(item)
                                .listRowBackground(Theme.surface)
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .curaBackground()
            .navigationTitle("予定")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        Task {
                            isSyncing = true
                            await model.syncCalendars()
                            isSyncing = false
                        }
                    } label: {
                        Image(systemName: isSyncing ? "arrow.triangle.2.circlepath" : "arrow.clockwise")
                    }
                    .disabled(isSyncing)

                    Button { showEditor = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showEditor) {
                EventEditorView(date: selectedDate).environmentObject(model)
            }
        }
    }

    private func row(_ item: ScheduleItem) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 10) {
                Text(item.timeLabel)
                    .font(.system(size: 14, design: .monospaced))
                    .foregroundStyle(Theme.cyan)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Theme.textPrimary)
                    Text(item.subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textTertiary)
                }
                Spacer()
                if item.isAttendanceTracked, item.attendanceStatus != AttendanceStatus.none {
                    Text(statusLabel(item.attendanceStatus))
                        .font(.system(size: 11, weight: .bold))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(statusColor(item.attendanceStatus))
                        .foregroundStyle(.white)
                        .clipShape(Capsule())
                }
            }

            if item.subtitle != "📝 タスク" {
                HStack(spacing: 8) {
                    ForEach([AttendanceStatus.attend, AttendanceStatus.late, AttendanceStatus.absent], id: \.self) { status in
                        Button(statusLabel(status)) {
                            model.record(status, subject: item.title, startMillis: item.sortTime)
                        }
                        .font(.system(size: 11))
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(item.attendanceStatus == status ? statusColor(status) : Theme.border)
                        .foregroundStyle(item.attendanceStatus == status ? .white : Theme.textSecondary)
                        .clipShape(Capsule())
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func statusLabel(_ status: AttendanceStatus) -> String {
        switch status {
        case AttendanceStatus.attend: return "出席"
        case AttendanceStatus.late: return "遅刻"
        case AttendanceStatus.absent: return "欠席"
        default: return "未記録"
        }
    }

    private func statusColor(_ status: AttendanceStatus) -> Color {
        switch status {
        case AttendanceStatus.attend: return Theme.hpBar
        case AttendanceStatus.late: return Theme.expBar
        case AttendanceStatus.absent: return Theme.error
        default: return Theme.textTertiary
        }
    }
}

struct EventEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    let date: Date

    @State private var genre = ""
    @State private var location = ""
    @State private var start: Date
    @State private var trackAttendance = false
    @State private var saveAsPreset = false
    @State private var selectedPreset: String?

    init(date: Date) {
        self.date = date
        _start = State(initialValue: date)
    }

    private var presets: [EventPreset] { model.cura.schedules.presets() }

    var body: some View {
        NavigationStack {
            Form {
                if !presets.isEmpty {
                    Section("保存済みから選択") {
                        ForEach(presets, id: \.genre) { preset in
                            Button {
                                genre = preset.genre
                                location = preset.location
                                if preset.hour >= 0 {
                                    start = Calendar.current.date(
                                        bySettingHour: Int(preset.hour),
                                        minute: Int(preset.minute),
                                        second: 0,
                                        of: date
                                    ) ?? date
                                }
                                selectedPreset = preset.genre
                            } label: {
                                HStack {
                                    Text(preset.genre)
                                    Spacer()
                                    if selectedPreset == preset.genre {
                                        Image(systemName: "checkmark").foregroundStyle(Theme.cyan)
                                    }
                                }
                            }
                            .foregroundStyle(Theme.textPrimary)
                        }
                        .onDelete { offsets in
                            for index in offsets {
                                model.cura.schedules.deletePreset(genre: presets[index].genre)
                            }
                        }
                    }
                }

                Section("内容") {
                    TextField("ジャンル (例: バイト、講義)", text: $genre)
                    TextField("場所", text: $location)
                    DatePicker("開始時間", selection: $start)
                }

                Section {
                    Toggle("出席/記録を管理する (授業など)", isOn: $trackAttendance)
                    Toggle("この内容をプリセットとして保存する", isOn: $saveAsPreset)
                }
            }
            .scrollContentBackground(.hidden)
            .curaBackground()
            .navigationTitle("予定の追加")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("追加") { Task { await save() } }
                        .disabled(genre.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() async {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: start)

        if saveAsPreset {
            model.cura.schedules.addPreset(
                preset: EventPreset(
                    genre: genre,
                    location: location,
                    hour: Int32(parts.hour ?? -1),
                    minute: Int32(parts.minute ?? -1)
                )
            )
        }

        await model.saveEvent(
            ScheduleEvent(
                id: UUID().uuidString,
                genre: genre,
                startTime: Int64(start.timeIntervalSince1970 * 1000),
                location: location,
                isPreset: false,
                isAttendanceTracked: trackAttendance,
                attendanceStatus: AttendanceStatus.none.name
            )
        )
        dismiss()
    }
}
