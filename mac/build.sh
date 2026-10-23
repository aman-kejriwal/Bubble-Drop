#!/bin/zsh
# Builds BubbleDropCore, the CLI and BubbleDrop.app with swiftc directly
# (works with Command Line Tools alone; `swift build` also works where SwiftPM is healthy).
set -euo pipefail
cd "$(dirname "$0")"

CONFIG=${1:-release}
OUT=build
TARGET=arm64-apple-macos15.0
FLAGS=(-swift-version 5 -target "$TARGET")
[[ $CONFIG == release ]] && FLAGS+=(-O) || FLAGS+=(-Onone -g)

mkdir -p "$OUT"

echo "› BubbleDropCore"
swiftc "${FLAGS[@]}" -parse-as-library -module-name BubbleDropCore \
  -emit-library -static -emit-module -emit-module-path "$OUT/BubbleDropCore.swiftmodule" \
  Sources/BubbleDropCore/*.swift -o "$OUT/libBubbleDropCore.a"

echo "› bubbledrop-cli"
swiftc "${FLAGS[@]}" -module-name bubbledrop_cli -I "$OUT" -L "$OUT" -lBubbleDropCore \
  Sources/bubbledrop-cli/main.swift -o "$OUT/bubbledrop-cli"

echo "› BubbleDrop.app"
APP="$OUT/BubbleDrop.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
swiftc "${FLAGS[@]}" -parse-as-library -module-name BubbleDrop -I "$OUT" -L "$OUT" -lBubbleDropCore \
  Sources/BubbleDrop/*.swift -o "$APP/Contents/MacOS/BubbleDrop"
cp Info.plist "$APP/Contents/Info.plist"

if [[ ! -f "$OUT/AppIcon.icns" ]]; then
  echo "› AppIcon"
  ICONSET="$OUT/AppIcon.iconset"
  mkdir -p "$ICONSET"
  swift make-icon.swift "$OUT/icon-1024.png"
  for px in 16 32 128 256 512; do
    sips -z $px $px "$OUT/icon-1024.png" --out "$ICONSET/icon_${px}x${px}.png" >/dev/null
    sips -z $((px * 2)) $((px * 2)) "$OUT/icon-1024.png" --out "$ICONSET/icon_${px}x${px}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET" -o "$OUT/AppIcon.icns"
  rm -rf "$ICONSET" "$OUT/icon-1024.png"
fi
mkdir -p "$APP/Contents/Resources"
cp "$OUT/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"

echo "› Share extension"
APPEX="$APP/Contents/PlugIns/BubbleDropShare.appex"
mkdir -p "$APPEX/Contents/MacOS"
swiftc "${FLAGS[@]}" -parse-as-library -module-name BubbleDropShare \
  -Xlinker -e -Xlinker _NSExtensionMain \
  ShareExtension/*.swift -o "$APPEX/Contents/MacOS/BubbleDropShare"
cp ShareExtension/Info.plist "$APPEX/Contents/Info.plist"
codesign --force --sign - --entitlements ShareExtension/ShareExtension.entitlements "$APPEX" >/dev/null

codesign --force --sign - "$APP" >/dev/null

echo "Built $OUT/bubbledrop-cli and $APP"
