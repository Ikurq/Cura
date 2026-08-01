import Shared
import SwiftUI

/// 通知をタップして開いたときの全画面。
///
/// 通知音は30秒で切れるため、ここで同じ音声を最初から最後まで鳴らす。
/// 「起床時の読み上げ」(予定 + タスク)も、この画面で合成して続けて再生する。
struct AlarmAlertView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    let alarmId: String

    @State private var now = Date()
    @State private var status = "読み上げの準備中…"

    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private var alarm: AlarmItem? { model.cura.alarms.find(id: alarmId) }

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Text(now, format: .dateTime.hour().minute())
                .font(.system(size: 64, weight: .thin, design: .monospaced))
                .foregroundStyle(Theme.textPrimary)

            if let alarm {
                Text(alarm.message)
                    .font(.system(size: 15))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 32)
                Text(alarm.speakerName)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.cyan)
            }

            Text(status)
                .font(.system(size: 12))
                .foregroundStyle(Theme.textTertiary)

            Spacer()

            Button {
                model.audio.stop()
                dismiss()
            } label: {
                Text("停止")
                    .font(.system(size: 18, weight: .bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
            }
            .background(Theme.error)
            .foregroundStyle(.white)
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .curaBackground()
        .onReceive(clock) { now = $0 }
        .task { await playAlarmThenBriefing() }
        .onDisappear { model.audio.stop() }
    }

    private func playAlarmThenBriefing() async {
        guard let alarm else {
            status = "アラームが見つかりません"
            return
        }

        // 1. アラーム本体
        let text = model.cura.alarmPlanner.speechText(alarm: alarm, forMillis: model.nowMillis())
        let fileName = alarmSoundFileName(alarmId: alarm.id, text: text, styleId: alarm.speakerId)

        status = "読み上げ中…"
        switch await model.voice.synthesize(text: text, styleId: alarm.speakerId, fileName: fileName) {
        case .success(let url):
            model.audio.play(url)
        case .modelMissing(let name):
            status = "\(name) の音声モデルが未取得です"
            return
        case .failure(let message):
            status = message
            return
        }

        // 2. 予定とタスクの読み上げ。無ければ何もしない。
        guard let briefing = model.cura.alarmPlanner.morningBriefingText(nowMillis: model.nowMillis()) else {
            status = ""
            return
        }

        // 本体の再生が終わるまで待ってから続ける
        while model.audio.isPlaying {
            try? await Task.sleep(for: .milliseconds(200))
        }

        status = "予定を確認中…"
        let briefingFile = alarmSoundFileName(alarmId: "briefing", text: briefing, styleId: alarm.speakerId)
        switch await model.voice.synthesize(text: briefing, styleId: alarm.speakerId, fileName: briefingFile) {
        case .success(let url):
            status = "本日の予定を読み上げています"
            model.audio.play(url)
        case .modelMissing, .failure:
            status = ""
        }
    }
}
