import Shared
import SwiftUI

/// 「絶対起きるアラーム」。予定を選び、その何分前に鳴らすかを決めて自動生成する。
///
/// 生成されるセリフには「本日の予定である」が含まれる。0時のリマインドは
/// この文言を手掛かりに「当日ぶんが設定済みか」を判定する(Android 版と同じ)。
struct MandatoryAlarmView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedIndex = 0
    @State private var leadMinutes = 30
    @State private var speakerId: Int32 = 3
    @State private var isSaving = false

    private var candidates: [(event: IcsEvent, isTomorrow: Bool)] {
        let today = CuraTime.shared.today()
        let tomorrow = CuraTime.shared.tomorrow()
        return model.cura.schedule.eventsOn(date: today).map { ($0, false) }
            + model.cura.schedule.eventsOn(date: tomorrow).map { ($0, true) }
    }

    private var voices: [VoiceService.VoiceChoice] { model.voice.availableVoices }

    var body: some View {
        NavigationStack {
            Form {
                if candidates.isEmpty {
                    Text("今日・明日の予定が見つかりません。先に予定を追加するか、カレンダーを同期してください。")
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                } else {
                    Section("対象の予定") {
                        Picker("予定", selection: $selectedIndex) {
                            ForEach(candidates.indices, id: \.self) { index in
                                let item = candidates[index]
                                Text("\(item.isTomorrow ? "[明日] " : "[今日] ")\(CuraTime.shared.formatHourMinute(millis: item.event.startTime)) \(item.event.summary)")
                                    .tag(index)
                            }
                        }
                    }

                    Section("何分前に鳴らすか") {
                        Stepper("\(leadMinutes) 分前", value: $leadMinutes, in: 5...240, step: 5)
                    }

                    Section("話者") {
                        if voices.isEmpty {
                            Text("音声モデルが未取得です。設定から取得してください。")
                                .font(.footnote)
                                .foregroundStyle(Theme.error)
                        } else {
                            Picker("キャラクター", selection: $speakerId) {
                                ForEach(voices) { Text($0.displayName).tag($0.styleId) }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .curaBackground()
            .navigationTitle("絶対起きるアラーム")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "生成中…" : "生成してセット") { Task { await generate() } }
                        .disabled(isSaving || candidates.isEmpty || voices.isEmpty)
                }
            }
            .onAppear { speakerId = model.cura.settings.lastSpeakerId }
        }
    }

    private func generate() async {
        guard candidates.indices.contains(selectedIndex),
              let voice = voices.first(where: { $0.styleId == speakerId }) ?? voices.first
        else { return }

        isSaving = true
        let event = candidates[selectedIndex].event
        let triggerMillis = event.startTime - Int64(leadMinutes) * 60_000
        let trigger = CuraTime.shared.toLocalDateTime(millis: triggerMillis)

        let message = "\(CuraTime.shared.speakHourMinute(hour: trigger.hour, minute: trigger.minute))を過ぎています。"
            + "本日の予定である\(event.summary)まであと\(leadMinutes)分を切っています。起きてください。"

        let alarm = AlarmItem(
            id: UUID().uuidString,
            hour: Int32(trigger.hour),
            minute: Int32(trigger.minute),
            message: message,
            speakerId: voice.styleId,
            speakerName: voice.characterName,
            isEnabled: true,
            readTasks: false,
            vibrate: true,
            repeatDays: []
        )

        model.cura.settings.lastSpeakerId = voice.styleId
        await model.saveAlarm(alarm)
        isSaving = false
        model.toast = "「絶対起きるアラーム」をセットしました"
        dismiss()
    }
}
