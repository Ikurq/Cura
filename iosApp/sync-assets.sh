#!/usr/bin/env bash
# キャラクター画像を app/src/main/res/drawable から Asset Catalog へ同期する。
#
# 同じ画像を Android と iOS で2重にコミットしたくないので、iOS 側の
# imageset は生成物として扱い、gitignore してある。xcodegen のビルド前
# スクリプトから呼ばれるほか、手で叩いてもよい。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DRAWABLE="$SCRIPT_DIR/../app/src/main/res/drawable"
CATALOG="$SCRIPT_DIR/Cura/Assets.xcassets"

IMAGES=(
  guardian_character
  guardian_character_casual
  guardian_character_summer
  guardian_character_summer_casual
)

for name in "${IMAGES[@]}"; do
  src="$DRAWABLE/$name.png"
  [[ -f "$src" ]] || { echo "error: $src がありません" >&2; exit 1; }

  dir="$CATALOG/$name.imageset"
  mkdir -p "$dir"
  # 中身が同じならタイムスタンプを変えない(不要な再ビルドを避ける)
  if ! cmp -s "$src" "$dir/$name.png"; then
    cp "$src" "$dir/$name.png"
  fi
  cat > "$dir/Contents.json" <<EOF
{
  "images" : [
    { "filename" : "$name.png", "idiom" : "universal", "scale" : "1x" },
    { "idiom" : "universal", "scale" : "2x" },
    { "idiom" : "universal", "scale" : "3x" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
EOF
done

# アプリアイコン
icon_dir="$CATALOG/AppIcon.appiconset"
mkdir -p "$icon_dir"
if [[ ! -f "$icon_dir/icon.png" ]] \
  || [[ "$DRAWABLE/cura_icon_character.png" -nt "$icon_dir/icon.png" ]]; then
  sips -s format png -z 1024 1024 "$DRAWABLE/cura_icon_character.png" \
    --out "$icon_dir/icon.png" >/dev/null
fi
cat > "$icon_dir/Contents.json" <<'EOF'
{
  "images" : [
    { "filename" : "icon.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
EOF

echo "synced $((${#IMAGES[@]} + 1)) images"
