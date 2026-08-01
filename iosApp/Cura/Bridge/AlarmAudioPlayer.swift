import AVFoundation
import Foundation

/// アラーム音声の再生。
///
/// 通知音は30秒で切れるので、通知から開いたときはここで最初から最後まで鳴らす。
/// 目覚まし用途なので、消音スイッチを無視する `.playback` カテゴリを使う。
@MainActor
final class AlarmAudioPlayer: NSObject, ObservableObject {

    @Published private(set) var isPlaying = false

    private var player: AVAudioPlayer?

    func play(_ url: URL, loops: Int = 0) {
        stop()
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)

            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.numberOfLoops = loops
            player.volume = 1
            player.prepareToPlay()
            player.play()
            self.player = player
            isPlaying = true
        } catch {
            isPlaying = false
        }
    }

    func stop() {
        player?.stop()
        player = nil
        isPlaying = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

extension AlarmAudioPlayer: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.isPlaying = false
            self.player = nil
        }
    }
}
