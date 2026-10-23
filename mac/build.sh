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
codesign --force --sign - "$APP" >/dev/null

echo "Built $OUT/bubbledrop-cli and $APP"
