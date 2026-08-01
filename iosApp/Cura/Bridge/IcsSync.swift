import Foundation
import Shared

/// 購読中の iCal を取得してキャッシュに取り込む。
///
/// パースは共通ロジック(`IcsParser`)側。ここは HTTP だけを持つ。
struct IcsSync {
    let cura: Cura

    /// 全ソースを取得してキャッシュを差し替える。
    ///
    /// 1件でも取得に失敗したら、キャッシュには手を付けない(取れた分だけで
    /// 上書きすると、失敗したソースの予定が消えてしまうため)。
    /// - Returns: キャッシュ内の予定件数と、取得に失敗したソース名。
    func refresh() async -> (imported: Int, failed: [String]) {
        let sources = cura.schedules.calendarSources()
        guard !sources.isEmpty else { return (0, []) }

        var bodies: [String] = []
        var failed: [String] = []

        await withTaskGroup(of: (String, String?).self) { group in
            for source in sources {
                group.addTask {
                    guard let url = URL(string: source.url) else { return (source.name, nil) }
                    do {
                        let (data, response) = try await URLSession.shared.data(from: url)
                        guard let http = response as? HTTPURLResponse,
                              (200..<300).contains(http.statusCode)
                        else { return (source.name, nil) }
                        return (source.name, String(decoding: data, as: UTF8.self))
                    } catch {
                        return (source.name, nil)
                    }
                }
            }
            for await (name, body) in group {
                if let body { bodies.append(body) } else { failed.append(name) }
            }
        }

        // キャッシュは全ソースぶんをまとめて1つに持っているので、取れた分だけで
        // 入れ替えると、失敗したソースの予定がキャッシュごと消えてしまう。
        // どのソース由来かを記録していない以上、部分的な差し替えはできないため、
        // 全部取れたときだけ入れ替える。
        guard failed.isEmpty else {
            return (Int(cura.schedules.icsEvents().count), failed)
        }
        cura.importIcs(bodies: bodies)
        return (Int(cura.schedules.icsEvents().count), failed)
    }
}
