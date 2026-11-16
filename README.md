# BubbleDrop

AirDrop-style file sharing between Android and Mac (and Mac↔Mac, Android↔Android)
over the same Wi-Fi network, encrypted with mutual TLS. Protocol: [PROTOCOL.md](PROTOCOL.md).

The first time two devices talk, both show a 6-digit code. Check that they match and tap
**Pair** on both. After that, transfers to that device go straight to the accept prompt.
Unpair any device from the "Paired devices" list.

## Mac

```sh
cd mac
./build.sh            # → build/BubbleDrop.app and build/bubbledrop-cli
open build/BubbleDrop.app # menu-bar icon (paper plane)
```

- Pick a nearby device → **Send…**, or drop files or folders onto its row. Folders arrive with
  their subfolders intact.
- Incoming offers show an Accept/Decline prompt; files go to `~/Downloads`.
- The clipboard button on a paired device's row sends the text on this Mac's clipboard to that
  device's clipboard. Text sent from the phone lands on this Mac's clipboard, ready to paste.
- macOS will ask for Local Network access the first time. Allow it. Requires macOS 15+.

CLI (handy for testing):

```sh
build/bubbledrop-cli serve --auto-accept --dir /tmp/in   # receive (auto-confirms pairing too)
build/bubbledrop-cli browse                               # list devices
build/bubbledrop-cli send "Pixel 9a" photo.jpg            # by name, or host:port
build/bubbledrop-cli text "Pixel 9a" "some text"          # put text on its clipboard
build/bubbledrop-cli paired [--forget FINGERPRINT]        # list / remove paired devices
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

- Open BubbleDrop and grant notifications (plus local network on Android 17).
- Tap a nearby device to pick files, tap **Folder** to send a whole folder, or use
  **Share → BubbleDrop** from any app.
- Incoming offers show a dialog or a heads-up notification with Accept/Decline;
  files go to `Download/BubbleDrop`.
- **Send Clipboard** (shown when the paired device is nearby) puts the text you last copied on
  the phone onto the other device's clipboard. You can also select text in any app and pick
  **BubbleDrop** from the selection menu, or share text to BubbleDrop. Text sent from the Mac is
  copied to the phone's clipboard and announced in a notification.
- **Use as Trackpad** (shown when the paired Mac is nearby) turns the phone into the Mac's
  trackpad: slide to move the pointer, tap to click, two fingers to scroll, two-finger tap to
  right-click, tap then slide to drag (lift and touch again within a moment to keep dragging),
  pinch to zoom, three fingers left or right to switch
  spaces and full-screen apps, three fingers up for Mission Control and down for the front
  app's windows. A scroll flicked with two fingers carries on and slows down, and clicks,
  drags and swipes tick under your finger. The buttons under the pad set the Mac's brightness and volume and play or
  pause its media. The keyboard button at the top opens the phone's keyboard to type on the
  Mac, with Esc, Tab and arrow keys under the pad. On the Mac, turn on **Use phone as trackpad
  and keyboard** in the BubbleDrop menu and
  allow BubbleDrop under Privacy & Security → Accessibility.

## Folder sync

Keep one folder identical on the Mac and the paired phone (protocol: PROTOCOL.md, "Folder sync").

- **Mac:** menu-bar panel → *Folder sync* → **Choose Folder…**
- **Android:** *Folder Sync* → **Choose Folder…**, then allow **All files access**. BubbleDrop
  needs the folder's real path to watch it live.
- Changes sync within seconds while BubbleDrop is on and both devices are on the same Wi-Fi.
  Anything missed syncs when the devices next see each other, and in a full round every 5 minutes.
- If both sides edit the same file, both versions are kept: one gets a
  `(conflict from <device> <date>)` copy. If one side edits and the other deletes, the edit wins.
- Replaced and deleted files go to the Trash on the Mac and to `.bubbledrop-trash/` (kept 30 days)
  on the phone. Hidden files, empty folders and symlinks aren't synced.
- Test it on one Mac: `bubbledrop-cli serve --state A --sync-folder FA --sync-with 127.0.0.1:<B's port>`
  (and the same for B) after pairing the two.

## Status / next steps

- [x] Discover → Ask → Upload, both directions, multi-file, progress
- [x] Mutual TLS with certificate pinning; one-time pairing by comparing 6-digit codes
- [ ] Trusted devices / auto-accept, contacts-only mode
- [ ] Folders, shared text/links, Finder Share extension
- [ ] BLE wake-up when not on the same Wi-Fi
