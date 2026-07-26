# voicevox-core-android (Kotlin)

[VOICEVOX CORE](https://github.com/VOICEVOX/voicevox_core) の Android 用 Kotlin ラッパー。suspend 関数ベースの Facade でテキスト読み上げ(TTS)を利用できます。

- 公式 `voicevoxcore-android` AAR(VOICEVOX CORE 0.16.4、JNI ブリッジ内蔵)への薄い idiomatic ラッパー
- Kotlin Coroutines(suspend / 並列ダウンロード)対応
- 音声モデル(.vvm)は**同梱せず実行時ダウンロード** + 利用規約への同意(LicenseGate)必須
- minSdk 26

## インストール

```kotlin
dependencies {
    implementation("jp.voicevox:voicevox-core-android:0.1.0")
}
```

### 依存する公式 AAR について(重要)

本ライブラリは公式 Java API `jp.hiroshiba.voicevoxcore:voicevoxcore-android:0.16.4` に `api` 依存しますが、この AAR は **Maven Central には公開されていません**(公式配布の `java_packages.zip` に Maven リポジトリ形式で同梱)。利用側では次のいずれかが必要です:

1. **本リポジトリと同じ方式でローカル Maven リポジトリとして展開する**: `./packages/core-native/scripts/fetch-core.sh android` → `./packages/android/scripts/prepare-binaries.sh` を実行すると `packages/android/local-maven/` に展開されるので、`settings.gradle.kts` の `dependencyResolutionManagement.repositories` に `maven(url = ".../local-maven")` を追加する
2. **上流の Maven 公開を待つ**: VOICEVOX 公式が voicevoxcore-android を公開リポジトリに配布し始めたら、リポジトリ追加のみで解決可能になる

## クイックスタート

```kotlin
import jp.voicevox.android.Voicevox

// 1. 初期化(辞書展開・ONNX Runtime ロード)。suspend
val voicevox = Voicevox.create(context)

// 2. モデル一覧(ダウンロード状態・キャラクター・スタイル付き)
val models = voicevox.listModels()

// 3. 利用規約を提示して同意を得てから acceptLicense(アプリ側の責務)
voicevox.acceptLicense("0")

// 4. ダウンロード(要同意)。複数は downloadModels / downloadAllModels
voicevox.downloadModel("0")

// 5. 合成(WAV の ByteArray が返る)。styleId 省略時は先頭 talk スタイル
val wav = voicevox.synthesis("こんにちは、ずんだもんなのだ", modelId = "0", styleId = 3)
```

`downloadModels(ids)` / `downloadAllModels()` は並列実行され、モデルごとの `Result` を返します(一部失敗でも例外を投げません)。

## 音声モデルのライセンス

- モデルのダウンロード・合成には `acceptLicense(modelId)` による**明示的な同意が必須**です
- 規約の提示 UI はアプリ側で実装してください(`example/` に同意ダイアログの実装例があります)
- 生成音声の公開・利用時は `creditText`(例: 「VOICEVOX:ずんだもん」)の**クレジット表記が必要**です
- 詳細は [docs/MODEL_LICENSING.md](../../docs/MODEL_LICENSING.md) を参照

## ライセンス

このパッケージのコードは MIT License です([LICENSE](LICENSE))。VOICEVOX CORE・音声モデルにはそれぞれの規約が適用されます。
