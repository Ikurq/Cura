import Shared
import SwiftUI

/// ホーム。キュラの立ち絵、ステータス、今日のダッシュボード、SYS_LOG。
struct HomeView: View {
    @EnvironmentObject private var model: AppModel

    @State private var systemLogIndex = 0
    @State private var consecutiveTaps = 0
    @State private var lastTapAt = Date.distantPast
    @State private var dialoguePage = 0
    @State private var lastInteraction = Date()

    private let logTimer = Timer.publish(every: 3, on: .main, in: .common).autoconnect()
    private let idleTimer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    statusCard
                    characterArea
                    dashboardCard
                    systemLogBar
                }
                .padding(16)
            }
            .curaBackground()
            .navigationTitle(model.cura.settings.userName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Theme.background, for: .navigationBar)
        }
        .onReceive(logTimer) { _ in
            systemLogIndex = (systemLogIndex + 1) % max(model.systemLogLines.count, 1)
        }
        .onReceive(idleTimer) { _ in
            // 最後の操作から1分でひとりごと
            if Date().timeIntervalSince(lastInteraction) > 60 {
                model.showIdleDialogue()
                dialoguePage = 0
                lastInteraction = Date()
            }
        }
        .onAppear { model.refreshAll() }
    }

    // MARK: - ステータス

    private var statusCard: some View {
        CyberCard {
            VStack(alignment: .leading, spacing: 12) {
                if model.cura.settings.showPlayerLevel {
                    levelRow(
                        title: "Lv.\(model.playerLevel.level) (RANK: MASTER)",
                        detail: "\(model.playerLevel.currentExp) / \(model.playerLevel.requiredExp) EXP",
                        info: model.playerLevel,
                        color: Theme.expBar
                    )
                }
                if model.cura.settings.showCharacterLevel {
                    levelRow(
                        title: "CURA Lv.\(model.characterLevel.level)",
                        detail: "\(model.characterLevel.currentExp)/\(model.characterLevel.requiredExp)",
                        info: model.characterLevel,
                        color: Theme.mpBar
                    )
                }
            }
        }
    }

    private func levelRow(title: String, detail: String, info: LevelInfo, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(detail)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
            }
            StatBar(value: Double(info.progress), color: color)
        }
    }

    // MARK: - キャラクター

    private var characterArea: some View {
        VStack(spacing: 8) {
            if let dialogue = model.dialogue {
                dialogueBubble(dialogue)
            }

            Image(costumeImageName)
                .resizable()
                .scaledToFit()
                .frame(maxHeight: 320)
                .onTapGesture { handleTap() }
                .accessibilityLabel("キュラ")
        }
    }

    private func dialogueBubble(_ dialogue: Dialogue) -> some View {
        let pages = dialogue.pages
        let page = pages.indices.contains(dialoguePage) ? pages[dialoguePage] : (pages.last ?? "")

        return CyberCard(accent: Theme.pink) {
            Text(page)
                .font(.system(size: 14))
                .foregroundStyle(Theme.textPrimary)
        }
        .onTapGesture {
            // スキップ不可のセリフは、タップで飛ばさず順送りだけ
            advanceDialogue(pages: pages)
        }
        // 文字数に応じて表示時間を決める(Android 版と同じ 1文字120ms + 300ms、最低1.5秒)
        .task(id: "\(dialoguePage)-\(dialogue.text.hashValue)") {
            let duration = max(Double(page.count) * 0.12 + 0.3, 1.5)
            try? await Task.sleep(for: .seconds(duration))
            advanceDialogue(pages: pages)
        }
    }

    private func advanceDialogue(pages: [String]) {
        if dialoguePage + 1 < pages.count {
            dialoguePage += 1
        } else {
            model.dialogue = nil
            dialoguePage = 0
        }
    }

    private func handleTap() {
        lastInteraction = Date()

        // 300ms 以内の連打を数える
        let now = Date()
        consecutiveTaps = now.timeIntervalSince(lastTapAt) < 0.3 ? consecutiveTaps + 1 : 1
        lastTapAt = now

        // スキップ不可のセリフを再生中は反応しない
        if let dialogue = model.dialogue, !dialogue.isSkippable { return }

        dialoguePage = 0
        model.tapCharacter(consecutiveTaps: consecutiveTaps)
    }

    /// 季節と曜日で変わる衣装。
    private var costumeImageName: String {
        switch model.costume {
        case .summerCasual: return "guardian_character_summer_casual"
        case .casual: return "guardian_character_casual"
        case .summer: return "guardian_character_summer"
        default: return "guardian_character"
        }
    }

    // MARK: - ダッシュボード

    private var dashboardCard: some View {
        CyberCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("TODAY")
                    .font(.system(size: 12, weight: .bold))
                    .italic()
                    .kerning(1)
                    .foregroundStyle(Theme.cyan)

                if model.todayItems.isEmpty {
                    Text("今日の予定はありません。")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                } else {
                    ForEach(model.todayItems, id: \.id) { item in
                        HStack(alignment: .top, spacing: 10) {
                            Text(item.timeLabel)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Theme.cyan)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.title)
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(Theme.textPrimary)
                                Text(item.subtitle)
                                    .font(.system(size: 11))
                                    .foregroundStyle(Theme.textTertiary)
                            }
                        }
                    }
                }

                Divider().background(Theme.border)

                if let next = model.cura.alarms.nextEnabled(fromMillis: model.nowMillis()) {
                    Label("次のアラーム \(next.timeLabel)", systemImage: "alarm")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                } else {
                    Label("アラーム未設定", systemImage: "alarm.slash")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.error)
                }
            }
        }
    }

    private var systemLogBar: some View {
        let systemLog = model.systemLogLines
        return Text(systemLog.indices.contains(systemLogIndex) ? systemLog[systemLogIndex] : "")
            .font(.system(size: 10, design: .monospaced))
            .foregroundStyle(Theme.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .lineLimit(1)
    }
}
