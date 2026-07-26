#!/usr/bin/env bash
# prepare-binaries.sh — Android パッケージのビルドに必要な取得物を配置する。
#   1. 公式 java_packages.zip → local-maven/(voicevoxcore-android AAR)
#   2. VOICEVOX ONNX Runtime .so → lib/src/main/jniLibs/
#   3. Open JTalk 辞書 + licenses.json → lib/src/main/assets/
# 事前に ../core-native/scripts/fetch-core.sh android を実行しておくこと。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(dirname "$SCRIPT_DIR")"
CORE="$PKG_DIR/../core-native"
DIST="$CORE/dist"
CACHE="$CORE/.cache"

# shellcheck disable=SC1091
source "$CORE/VERSION"

[[ -d "$DIST/android" ]] || {
  echo "error: run ../core-native/scripts/fetch-core.sh android first" >&2
  exit 1
}

# --- 1. 公式 Java パッケージ(Mavenレイアウトzip)を local-maven に展開 ---
zip="$CACHE/java_packages.zip"
if [[ ! -f "$zip" ]]; then
  echo "fetch: java_packages.zip"
  curl -fL --retry 3 -o "$zip" \
    "https://github.com/VOICEVOX/voicevox_core/releases/download/${VOICEVOX_CORE_VERSION}/java_packages.zip"
fi
rm -rf "$PKG_DIR/local-maven"
mkdir -p "$PKG_DIR/local-maven"
unzip -oq "$zip" -d "$PKG_DIR/local-maven"

# --- 2. ONNX Runtime .so を jniLibs に配置 ---
jni="$PKG_DIR/lib/src/main/jniLibs"
rm -rf "$jni"
mkdir -p "$jni/arm64-v8a" "$jni/x86_64"
cp "$DIST/android/voicevox_onnxruntime-android-arm64-${VOICEVOX_ONNXRUNTIME_VERSION}/lib/libvoicevox_onnxruntime.so" "$jni/arm64-v8a/"
cp "$DIST/android/voicevox_onnxruntime-android-x64-${VOICEVOX_ONNXRUNTIME_VERSION}/lib/libvoicevox_onnxruntime.so" "$jni/x86_64/"

# --- 3. アセット(辞書 + licenses.json) ---
assets="$PKG_DIR/lib/src/main/assets"
rm -rf "$assets/open_jtalk_dic"
mkdir -p "$assets"
cp -R "$DIST/common/open_jtalk_dic_utf_8-${OPEN_JTALK_DICT_VERSION}" "$assets/open_jtalk_dic"
cp "$CORE/generated/licenses.json" "$assets/licenses.json"

echo "done."
