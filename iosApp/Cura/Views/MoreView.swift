import SwiftUI

/// タブは5個までしか並べられないので、6個目以降は自前のこの画面にまとめる。
///
/// システム任せの "More" タブに落とすと、iOS が用意する素の UINavigationController が
/// 挟まって背景が黒いままになり、各画面が持つ NavigationStack と二重になって
/// 戻るボタンが2つ並んでしまう。ここで NavigationStack を1つだけ持つ。
struct MoreView: View {
    var body: some View {
        NavigationStack {
            List {
                // ラベルは Android の nav_attendance / nav_settings に合わせる。
                NavigationLink {
                    AttendanceView()
                } label: {
                    Label("Link (出欠管理)", systemImage: "chart.bar.fill")
                }
                .listRowBackground(Theme.surface)

                NavigationLink {
                    SettingsView()
                } label: {
                    Label("Config (設定)", systemImage: "gearshape.fill")
                }
                .listRowBackground(Theme.surface)
            }
            .scrollContentBackground(.hidden)
            .curaBackground()
            .navigationTitle("その他")
        }
    }
}
