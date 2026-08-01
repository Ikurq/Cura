import Shared
import SwiftUI

/// 音声モデルの管理とストレージ。Android 版 Cura の「音声・ストレージ」に対応する。
struct VoiceModelsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var pendingTerms: VoiceService.Model?
    @State private var pendingDelete: VoiceService.Model?

    private var voice: VoiceService { model.voice }

    var body: some View {
        List {
            Section {
                Text("音声はすべて端末内で合成します。使いたいキャラクターの音声モデルを取得してください（オフラインでも動作します）。")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)

                HStack {
                    Text("取得済み")
                    Spacer()
                    Text("\(voice.models.filter(\.isDownloaded).count) / \(voice.models.count) ・ \(formatMB(voice.downloadedBytes))")
                        .foregroundStyle(Theme.textSecondary)
                }

                if let url = voice.termsURL {
                    Link("音声モデル利用規約を開く", destination: url)
                        .font(.footnote)
                }
            }

            Section("音声モデル") {
                ForEach(voice.models) { row($0) }
            }

            Section("ストレージ管理") {
                HStack {
                    VStack(alignment: .leading) {
                        Text("生成済みの音声")
                        Text(formatMB(voice.generatedAudioBytes))
                            .font(.caption).foregroundStyle(Theme.textTertiary)
                    }
                    Spacer()
                    Button("削除") { voice.clearGeneratedAudio() }
                        .foregroundStyle(Theme.error)
                }
                Text("削除しても、アラームを開き直せば作り直されます。")
                    .font(.caption).foregroundStyle(Theme.textTertiary)
            }
        }
        .scrollContentBackground(.hidden)
        .curaBackground()
        .navigationTitle("音声・ストレージ")
        .onAppear { voice.reload() }
        .alert(item: $pendingTerms) { model in termsAlert(model) }
        .confirmationDialog(
            "音声モデルを削除しますか？",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            presenting: pendingDelete
        ) { target in
            Button("削除", role: .destructive) {
                Task {
                    await voice.delete(target.id)
                    pendingDelete = nil
                }
            }
            Button("キャンセル", role: .cancel) { pendingDelete = nil }
        } message: { target in
            Text("\(target.title) の音声モデルを削除します。この声を使っているアラームは、次回の音声生成ができなくなります。")
        }
    }

    private func row(_ item: VoiceService.Model) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                    Text(detailText(item))
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                if item.isDownloading {
                    ProgressView().tint(Theme.cyan)
                } else if item.isDownloaded {
                    Button("削除") { pendingDelete = item }
                        .buttonStyle(.bordered)
                        .tint(Theme.error)
                } else {
                    Button("取得") { pendingTerms = item }
                        .buttonStyle(.bordered)
                        .tint(Theme.cyan)
                }
            }
            if item.isDownloading {
                ProgressView(value: item.progress).tint(Theme.cyan)
            }
        }
    }

    private func detailText(_ item: VoiceService.Model) -> String {
        let size = formatMB(item.sizeBytes)
        if item.isDownloading {
            return "取得中… \(formatMB(item.downloadedBytes)) / \(size)"
        }
        let styles = "\(item.voices.count) スタイル"
        return item.isDownloaded ? "取得済み ・ \(size) ・ \(styles)" : "未取得 ・ \(size) ・ \(styles)"
    }

    /// 規約とクレジット表記を出したうえで同意を取る。同意なしでは取得できない。
    private func termsAlert(_ item: VoiceService.Model) -> Alert {
        Alert(
            title: Text("利用規約への同意"),
            message: Text(
                "\(item.title) の音声モデル（\(formatMB(item.sizeBytes))）を取得します。\n\n"
                    + "利用にはVOICEVOXの音声モデル利用規約への同意が必要です。"
                    + "生成した音声を公開・配布する場合は次のクレジット表記が必要です。\n\n"
                    + item.creditTexts.map { "・\($0)" }.joined(separator: "\n")
            ),
            primaryButton: .default(Text("同意して取得")) {
                Task { await voice.download(item.id) }
            },
            secondaryButton: .cancel(Text("キャンセル"))
        )
    }

    private func formatMB(_ bytes: Int64) -> String {
        String(format: "%.1f MB", Double(bytes) / 1_048_576)
    }
}
