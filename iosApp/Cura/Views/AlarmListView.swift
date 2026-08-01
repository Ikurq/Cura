import Shared
import SwiftUI

private let weekdayLabels = ["日", "月", "火", "水", "木", "金", "土"]

struct AlarmListView: View {
    @EnvironmentObject private var model: AppModel

    @State private var editing: AlarmDraft?
    @State private var showMandatory = false
    @State private var isBusy = false

    var body: some View {
        NavigationStack {
            Group {
                if model.alarms.isEmpty {
                    emptyState
                } else {
                    List {
                        ForEach(model.alarms, id: \.id) { alarm in
                            row(alarm)
                                .listRowBackground(Theme.surface)
                        }
                        .onDelete { offsets in
                            Task {
                                for index in offsets { await model.deleteAlarm(model.alarms[index]) }
                            }
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .curaBackground()
            .navigationTitle("アラーム")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        showMandatory = true
                    } label: {
                        Image(systemName: "exclamationmark.triangle")
                    }
                    Button {
                        editing = AlarmDraft(defaultSpeakerId: model.cura.settings.lastSpeakerId)
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(item: $editing) { draft in
                AlarmEditorView(draft: draft)
                    .environmentObject(model)
            }
            .sheet(isPresented: $showMandatory) {
                MandatoryAlarmView()
                    .environmentObject(model)
            }
            .overlay { if isBusy { ProgressView().tint(Theme.cyan) } }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "alarm").font(.largeTitle).foregroundStyle(Theme.textTertiary)
            Text("アラームが設定されていません")
                .foregroundStyle(Theme.textSecondary)
            Text("＋ボタンから新しく作成しましょう")
                .font(.footnote)
                .foregroundStyle(Theme.textTertiary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func row(_ alarm: AlarmItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(alarm.timeLabel)
                    .font(.system(size: 30, weight: .light, design: .monospaced))
                    .foregroundStyle(alarm.isEnabled ? Theme.textPrimary : Theme.textTertiary)
                Text(alarm.speakerName)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.cyan)
                Text(alarm.message)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
                if !alarm.repeatDays.isEmpty {
                    Text(alarm.repeatDays.map { weekdayLabels[Int(truncating: $0) - 1] }.joined(separator: " "))
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textTertiary)
                }
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { alarm.isEnabled },
                set: { enabled in Task { await model.setAlarmEnabled(alarm, enabled: enabled) } }
            ))
            .labelsHidden()
            .tint(Theme.cyan)
        }
        .contentShape(Rectangle())
        .onTapGesture { editing = AlarmDraft(alarm: alarm) }
    }
}

/// 編集中のアラーム。`AlarmItem` は不変なので、画面用に可変の入れ物を持つ。
final class AlarmDraft: ObservableObject, Identifiable {
    let id: String
    @Published var hour: Int
    @Published var minute: Int
    @Published var message: String
    @Published var speakerId: Int32
    @Published var readTasks: Bool
    @Published var vibrate: Bool
    /// 1=日 … 7=土。
    @Published var repeatDays: Set<Int>

    let isNew: Bool

    init(defaultSpeakerId: Int32) {
        id = UUID().uuidString
        hour = 7
        minute = 0
        message = "時間です。起きてください。"
        speakerId = defaultSpeakerId
        readTasks = false
        vibrate = true
        repeatDays = []
        isNew = true
    }

    init(alarm: AlarmItem) {
        id = alarm.id
        hour = Int(alarm.hour)
        minute = Int(alarm.minute)
        message = alarm.message
        speakerId = alarm.speakerId
        readTasks = alarm.readTasks
        vibrate = alarm.vibrate
        repeatDays = Set(alarm.repeatDays.map { Int(truncating: $0) })
        isNew = false
    }

    func build(speakerName: String) -> AlarmItem {
        AlarmItem(
            id: id,
            hour: Int32(hour),
            minute: Int32(minute),
            message: message.isEmpty ? "時間です。起きてください。" : message,
            speakerId: speakerId,
            speakerName: speakerName,
            isEnabled: true,
            readTasks: readTasks,
            vibrate: vibrate,
            repeatDays: repeatDays.sorted().map { KotlinInt(int: Int32($0)) }
        )
    }
}

struct AlarmEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var draft: AlarmDraft

    @State private var isSaving = false
    @State private var isPreviewing = false

    private var voices: [VoiceService.VoiceChoice] { model.voice.availableVoices }

    var body: some View {
        NavigationStack {
            Form {
                Section("時刻") {
                    DatePicker(
                        "",
                        selection: Binding(
                            get: {
                                Calendar.current.date(
                                    bySettingHour: draft.hour, minute: draft.minute, second: 0, of: Date()
                                ) ?? Date()
                            },
                            set: { date in
                                let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
                                draft.hour = parts.hour ?? 7
                                draft.minute = parts.minute ?? 0
                            }
                        ),
                        displayedComponents: .hourAndMinute
                    )
                    .datePickerStyle(.wheel)
                    .labelsHidden()
                }

                Section("繰り返し") {
                    HStack(spacing: 6) {
                        ForEach(1...7, id: \.self) { day in
                            let selected = draft.repeatDays.contains(day)
                            Button(weekdayLabels[day - 1]) {
                                if selected { draft.repeatDays.remove(day) } else { draft.repeatDays.insert(day) }
                            }
                            .font(.system(size: 13, weight: .medium))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(selected ? Theme.accent : Theme.border)
                            .foregroundStyle(selected ? .white : Theme.textSecondary)
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                        }
                    }
                    .buttonStyle(.plain)
                }

                Section("セリフ") {
                    TextField("アラーム時に読み上げるセリフ", text: $draft.message, axis: .vertical)
                        .lineLimit(2...4)
                    Toggle("当日のタスクも読み上げる", isOn: $draft.readTasks)
                }

                Section("話者") {
                    if voices.isEmpty {
                        Text("音声モデルが未取得です。設定 ＞ 音声・ストレージ から取得してください。")
                            .font(.footnote)
                            .foregroundStyle(Theme.error)
                    } else {
                        Picker("キャラクター", selection: $draft.speakerId) {
                            ForEach(voices) { voice in
                                Text(voice.displayName).tag(voice.styleId)
                            }
                        }
                        Button {
                            Task { await preview() }
                        } label: {
                            Label(isPreviewing ? "生成中…" : "試聴", systemImage: "speaker.wave.2")
                        }
                        .disabled(isPreviewing)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .curaBackground()
            .navigationTitle(draft.isNew ? "新しいアラーム" : "アラームを編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "生成中…" : "保存") { Task { await save() } }
                        .disabled(isSaving || voices.isEmpty)
                }
            }
        }
    }

    private func save() async {
        guard let voice = voices.first(where: { $0.styleId == draft.speakerId }) ?? voices.first else { return }
        isSaving = true
        model.cura.settings.lastSpeakerId = voice.styleId
        await model.saveAlarm(draft.build(speakerName: voice.characterName))
        isSaving = false
        dismiss()
    }

    private func preview() async {
        guard let voice = voices.first(where: { $0.styleId == draft.speakerId }) else { return }
        isPreviewing = true
        let text = "これは試聴です。\(draft.message)"
        let result = await model.voice.synthesize(
            text: text,
            styleId: voice.styleId,
            fileName: "preview-\(voice.styleId).wav"
        )
        isPreviewing = false

        switch result {
        case .success(let url):
            model.audio.play(url)
        case .modelMissing(let name):
            model.toast = "\(name) の音声モデルが未取得です"
        case .failure(let message):
            model.toast = message
        }
    }
}
