import Shared
import SwiftUI

/// 出欠管理カウンター。科目ごとの出席・遅刻・欠席と、欠席日の履歴。
struct AttendanceView: View {
    @EnvironmentObject private var model: AppModel
    @State private var detail: SubjectStats?

    var body: some View {
        NavigationStack {
            Group {
                if model.attendance.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "chart.bar").font(.largeTitle).foregroundStyle(Theme.textTertiary)
                        Text("追跡中の科目がありません")
                            .foregroundStyle(Theme.textSecondary)
                        Text("予定画面で「出席/記録を管理する」を有効にすると集計されます")
                            .font(.footnote)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(Theme.textTertiary)
                            .padding(.horizontal, 32)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(model.attendance, id: \.name) { stats in
                        row(stats)
                            .listRowBackground(Theme.surface)
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .curaBackground()
            .navigationTitle("出欠管理カウンター")
            .sheet(item: Binding(
                get: { detail.map { SubjectDetail(stats: $0) } },
                set: { detail = $0?.stats }
            )) { wrapper in
                absentHistory(wrapper.stats)
            }
        }
    }

    private func row(_ stats: SubjectStats) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(stats.name)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                Text("全 \(stats.totalScheduled) 回")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textTertiary)
            }

            HStack(spacing: 16) {
                counter("出席", stats.attended, Theme.hpBar)
                counter("遅刻", stats.late, Theme.expBar)
                counter("欠席", stats.absent, Theme.error)

                Spacer()

                // 記録漏れの補正用。集計値がこれを下回る場合はこちらが採用される。
                HStack(spacing: 4) {
                    Button {
                        model.cura.attendance.adjustManualAbsent(subject: stats.name, delta: -1)
                        model.refreshAll()
                    } label: { Image(systemName: "minus.circle") }
                    Button {
                        model.cura.attendance.adjustManualAbsent(subject: stats.name, delta: 1)
                        model.refreshAll()
                    } label: { Image(systemName: "plus.circle") }
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.textSecondary)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { detail = stats }
    }

    private func counter(_ label: String, _ value: Int32, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(.system(size: 18, weight: .bold, design: .monospaced))
                .foregroundStyle(color)
            Text(label)
                .font(.system(size: 10))
                .foregroundStyle(Theme.textTertiary)
        }
    }

    private func absentHistory(_ stats: SubjectStats) -> some View {
        NavigationStack {
            List {
                if stats.absentDates.isEmpty {
                    Text("記録された欠席日はありません。\n(手動カウンターのみの可能性があります)")
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                } else {
                    ForEach(stats.absentDates, id: \.self) { date in
                        Text("・\(date) (欠席)")
                            .foregroundStyle(Theme.textPrimary)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .curaBackground()
            .navigationTitle("\(stats.name) の欠席履歴")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct SubjectDetail: Identifiable {
    let stats: SubjectStats
    var id: String { stats.name }
}
