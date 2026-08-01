import CryptoKit
import Foundation
import Shared
import VoicevoxCore

/// 端末内の音声合成。vv-mobile の `VoicevoxCore`(SwiftPM)を包む。
///
/// カタログ(`VoicevoxCatalog`)は JSON を読むだけなので同期的に使える。
/// 実際の合成・ダウンロードに要る `Voicevox` は ONNX Runtime と Open JTalk 辞書の
/// 読み込みを伴うので、必要になるまで作らない。
@MainActor
final class VoiceService: ObservableObject {

    /// モデル1件の表示状態。
    struct Model: Identifiable {
        let id: String
        let title: String
        let sizeBytes: Int64
        let creditTexts: [String]
        let voices: [VoiceChoice]
        var isDownloaded: Bool
        var downloadedBytes: Int64 = 0
        var isDownloading: Bool = false

        var progress: Double {
            guard isDownloading, sizeBytes > 0 else { return 0 }
            return min(1, Double(downloadedBytes) / Double(sizeBytes))
        }
    }

    /// 選択できる声1件。`styleId` は VOICEVOX のスタイルID。
    struct VoiceChoice: Identifiable, Hashable {
        let styleId: Int32
        let characterName: String
        let styleName: String
        let modelId: String

        var id: Int32 { styleId }
        var displayName: String {
            styleName == "ノーマル" ? characterName : "\(characterName)（\(styleName)）"
        }
    }

    @Published private(set) var models: [Model] = []
    @Published private(set) var isEngineLoading = false

    /// 合成した WAV の置き場。通知音として使えるよう Library/Sounds に置く。
    static var soundsDirectory: URL {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        let sounds = library.appendingPathComponent("Sounds", isDirectory: true)
        try? FileManager.default.createDirectory(at: sounds, withIntermediateDirectories: true)
        return sounds
    }

    private var catalog: VoicevoxCatalog?
    private var engine: Voicevox?

    init() {
        catalog = try? VoicevoxCatalog()
        reload()
    }

    // MARK: - カタログ

    func reload() {
        guard let catalog else {
            models = []
            return
        }
        let previous = Dictionary(uniqueKeysWithValues: models.map { ($0.id, $0) })

        models = catalog.models().compactMap { info in
            let voices = info.characters.flatMap { character in
                character.talkStyles.map { style in
                    VoiceChoice(
                        styleId: Int32(style.id),
                        characterName: character.name,
                        styleName: style.name,
                        modelId: info.id
                    )
                }
            }
            guard !voices.isEmpty else { return nil }

            return Model(
                id: info.id,
                title: info.characters.map(\.name).joined(separator: "、"),
                sizeBytes: info.sizeBytes,
                creditTexts: info.characters.map(\.creditText),
                voices: voices,
                isDownloaded: info.isDownloaded,
                downloadedBytes: previous[info.id]?.downloadedBytes ?? 0,
                isDownloading: previous[info.id]?.isDownloading ?? false
            )
        }
    }

    /// 取得済みモデルに含まれる、選べる声。
    var availableVoices: [VoiceChoice] {
        models.filter(\.isDownloaded).flatMap(\.voices)
    }

    func voice(forStyle styleId: Int32) -> VoiceChoice? {
        models.flatMap(\.voices).first { $0.styleId == styleId }
    }

    var termsURL: URL? {
        catalog.flatMap { URL(string: $0.termsURL) }
    }

    /// 取得済みモデルの合計サイズ。
    var downloadedBytes: Int64 { catalog?.downloadedSize() ?? 0 }

    /// 生成音声を利用する際に必要なクレジット表記。
    var creditTexts: [String] {
        Array(Set(models.filter(\.isDownloaded).flatMap(\.creditTexts))).sorted()
    }

    // MARK: - モデルの取得と削除

    /// 利用規約への同意を取ったうえで呼ぶこと。
    func download(_ modelId: String) async {
        guard let index = models.firstIndex(where: { $0.id == modelId }) else { return }
        models[index].isDownloading = true
        models[index].downloadedBytes = 0

        do {
            let engine = try await engine()
            engine.acceptLicense(modelId: modelId)
            try await engine.downloadModel(id: modelId) { [weak self] done, total in
                Task { @MainActor in
                    guard let self, let index = self.models.firstIndex(where: { $0.id == modelId }) else { return }
                    self.models[index].downloadedBytes = done
                    _ = total
                }
            }
            models[index].isDownloading = false
            models[index].isDownloaded = true
        } catch {
            models[index].isDownloading = false
            lastError = error.localizedDescription
        }
        reload()
    }

    func delete(_ modelId: String) async {
        do {
            try await engine().deleteModel(modelId)
        } catch {
            lastError = error.localizedDescription
        }
        reload()
    }

    // MARK: - 合成

    enum SynthesisResult {
        case success(URL)
        /// モデルが未取得。設定画面へ誘導する。
        case modelMissing(characterName: String)
        case failure(String)
    }

    /// テキストを合成して Library/Sounds に WAV を書き出す。
    ///
    /// 同じ(テキスト, 話者)の組はファイル名が一致するので、既にあれば作り直さない。
    func synthesize(text: String, styleId: Int32, fileName: String) async -> SynthesisResult {
        let url = Self.soundsDirectory.appendingPathComponent(fileName)
        if FileManager.default.fileExists(atPath: url.path) {
            return .success(url)
        }

        guard let voice = voice(forStyle: styleId) else {
            return .failure("この話者は現在利用できません")
        }
        guard models.first(where: { $0.id == voice.modelId })?.isDownloaded == true else {
            return .modelMissing(characterName: voice.characterName)
        }

        do {
            isEngineLoading = engine == nil
            let engine = try await engine()
            isEngineLoading = false

            let wav = try await engine.synthesis(
                text: text,
                modelId: voice.modelId,
                styleId: UInt32(styleId)
            )
            try wav.write(to: url, options: .atomic)
            return .success(url)
        } catch {
            isEngineLoading = false
            return .failure(error.localizedDescription)
        }
    }

    /// 生成済みの音声をすべて捨てる。
    func clearGeneratedAudio() {
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: Self.soundsDirectory, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "wav" {
            try? fm.removeItem(at: file)
        }
    }

    var generatedAudioBytes: Int64 {
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(
            at: Self.soundsDirectory,
            includingPropertiesForKeys: [.fileSizeKey]
        )) ?? []
        return files.reduce(0) { total, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return total + Int64(size)
        }
    }

    @Published var lastError: String?

    // MARK: -

    private func engine() async throws -> Voicevox {
        if let engine { return engine }
        let created = try Voicevox()
        engine = created
        return created
    }
}

/// アラーム音声のファイル名。テキストと話者から決まるので、同じ内容なら作り直さない。
///
/// Swift の `Hasher` はプロセスごとに種が変わるため、起動を跨いでキャッシュを
/// 再利用するにはこちらのように内容から決まるダイジェストが要る。
func alarmSoundFileName(alarmId: String, text: String, styleId: Int32) -> String {
    let digest = SHA256.hash(data: Data("\(text)|\(styleId)".utf8))
        .prefix(8)
        .map { String(format: "%02x", $0) }
        .joined()
    return "alarm-\(alarmId)-\(digest).wav"
}
