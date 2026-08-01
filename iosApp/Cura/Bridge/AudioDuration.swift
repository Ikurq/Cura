import AVFoundation
import Foundation

/// 音声ファイルの長さを測る。
///
/// iOS の通知音は30秒を超えると**再生されず既定音に落ちる**(途中で切れるのではない)ので、
/// 通知に載せる前にここで確かめる。
enum AudioDuration {
    static func seconds(of url: URL) -> Double? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let rate = file.fileFormat.sampleRate
        guard rate > 0 else { return nil }
        return Double(file.length) / rate
    }
}
