#!/bin/zsh
# Builds MyDropCore, the CLI and MyDrop.app with swiftc directly
# (works with Command Line Tools alone; `swift build` also works where SwiftPM is healthy).
set -euo pipefail
cd "$(dirname "$0")"

CONFIG=${1:-release}
OUT=build
TARGET=arm64-apple-macos15.0
FLAGS=(-swift-version 5 -target "$TARGET")
[[ $CONFIG == release ]] && FLAGS+=(-O) || FLAGS+=(-Onone -g)

mkdir -p "$OUT"

echo "› MyDropCore"
swiftc "${FLAGS[@]}" -parse-as-library -module-name MyDropCore \
  -emit-library -static -emit-module -emit-module-path "$OUT/MyDropCore.swiftmodule" \
  Sources/MyDropCore/*.swift -o "$OUT/libMyDropCore.a"

echo "› mydrop-cli"
swiftc "${FLAGS[@]}" -module-name mydrop_cli -I "$OUT" -L "$OUT" -lMyDropCore \
  Sources/mydrop-cli/main.swift -o "$OUT/mydrop-cli"

echo "› MyDrop.app"
APP="$OUT/MyDrop.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
swiftc "${FLAGS[@]}" -parse-as-library -module-name MyDrop -I "$OUT" -L "$OUT" -lMyDropCore \
  Sources/MyDrop/*.swift -o "$APP/Contents/MacOS/MyDrop"
cp Info.plist "$APP/Contents/Info.plist"
codesign --force --sign - "$APP" >/dev/null

echo "Built $OUT/mydrop-cli and $APP"
