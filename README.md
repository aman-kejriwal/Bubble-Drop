# MyDrop

AirDrop-style file sharing between Android and Mac (and Mac↔Mac, Android↔Android)
over the same Wi-Fi network, encrypted with mutual TLS. Protocol: [PROTOCOL.md](PROTOCOL.md).

The first time two devices talk, both show a 6-digit code. Check that they match and tap
**Pair** on both. After that, transfers to that device go straight to the accept prompt.
Unpair any device from the "Paired devices" list.

## Mac

```sh
cd mac
./build.sh            # → build/MyDrop.app and build/mydrop-cli
open build/MyDrop.app # menu-bar icon (paper plane)
```

- Pick a nearby device → **Send…**, or drop files onto its row.
- Incoming offers show an Accept/Decline prompt; files go to `~/Downloads`.
- macOS will ask for Local Network access the first time. Allow it. Requires macOS 15+.

CLI (handy for testing):

```sh
build/mydrop-cli serve --auto-accept --dir /tmp/in   # receive (auto-confirms pairing too)
build/mydrop-cli browse                               # list devices
build/mydrop-cli send "Pixel 9a" photo.jpg            # by name, or host:port
build/mydrop-cli paired [--forget FINGERPRINT]        # list / remove paired devices
# --state DIR gives an instance its own identity, e.g. to test two "devices" on one Mac
```

`build.sh` calls `swiftc` directly because SwiftPM on this machine's Command
Line Tools can't link manifests; `swift build` works with a healthy toolchain.

## Android

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Open MyDrop and grant notifications (plus local network on Android 17).
- Tap a nearby device to pick files, or use **Share → MyDrop** from any app.
- Incoming offers show a dialog or a heads-up notification with Accept/Decline;
  files go to `Download/MyDrop`.

## Status / next steps

- [x] Discover → Ask → Upload, both directions, multi-file, progress
- [x] Mutual TLS with certificate pinning; one-time pairing by comparing 6-digit codes
- [ ] Trusted devices / auto-accept, contacts-only mode
- [ ] Folders, shared text/links, Finder Share extension
- [ ] BLE wake-up when not on the same Wi-Fi
