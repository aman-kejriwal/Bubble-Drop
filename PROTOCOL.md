# BubbleDrop protocol v2

An AirDrop-style flow (Discover → Pair → Ask → Upload), rebuilt on transports that both
macOS and Android support. Apple's AirDrop runs over AWDL with Apple ID certificates;
BubbleDrop runs over the shared Wi-Fi/LAN with self-signed certificates and one-time pairing.

## Identity

Each device has a long-lived P-256 key and a self-signed X.509 certificate (macOS: a PKCS#12
file in `~/Library/Application Support/BubbleDrop`; Android: the Android Keystore). A device is
identified by its **fingerprint**: lowercase hex SHA-256 of the certificate's DER bytes.

## Discovery

Each receiver advertises a DNS-SD (Bonjour / NSD) service:

- Type: `_bubbledrop._tcp`
- Instance name: the device's human-readable name (the system may add a suffix)
- TXT record:

| key        | value                                       |
|------------|---------------------------------------------|
| `id`       | stable random device ID (UUID)              |
| `name`     | device name                                 |
| `model`    | hardware model, e.g. `Mac14,2`              |
| `platform` | `macos` or `android`                        |
| `v`        | protocol version, `2`                       |
| `fp`       | certificate fingerprint (UI hint, not trust) |

A browser ignores results whose `id` equals its own.

## Transport

HTTP/1.1 over **mutual TLS** (TLS 1.2+) on the advertised port. Both sides present their
certificate; the server requires one. Certificates are self-signed, so chain validation is
replaced by fingerprint checks:

- The server reads the client's fingerprint from the handshake and authorizes per endpoint.
- The client learns the server's fingerprint from Discover and **pins** it for every later
  request in the transfer: a different certificate fails the handshake.

Every response carries `Content-Length` and `Connection: close`; each request uses a new
connection. JSON bodies are UTF-8 and capped at 1 MiB. Binary values in JSON are base64.

## Types

```jsonc
// DeviceInfo
{ "id": "…uuid…", "name": "Aman's MacBook", "model": "Mac14,2",
  "platform": "macos", "protocolVersion": 2 }

// FileOffer
{ "id": "…uuid…", "name": "photo.jpg", "size": 123456, "mimeType": "image/jpeg",
  "path": "Trip/Day 1/photo.jpg" }   // optional: only for a file in a sent folder
```

Sending a folder offers each file in it (hidden files, symlinks and empty folders left out), with
`path` giving its place from and including the sent folder. `name` stays the bare file name, so a
receiver that predates `path` still saves every file, just without the folders.

Errors return `{ "error": "message" }` with a non-2xx status.

## Endpoints

### `POST /api/v2/discover`
Body: the sender's `DeviceInfo`. Response `200`:
`{ "device": DeviceInfo, "paired": bool }`, where `paired` says whether the server trusts
the client's certificate.

The client may skip pairing only if `paired` is true **and** it trusts the server's
fingerprint itself. Otherwise it must pair first. The apps pair only when the user asks to
(a Pair button, or the suggestion shown after BubbleDrop is turned on); sending, sharing and
mirroring never start pairing on their own and report the device as unpaired instead.

A device pairs with one other device only. While it has any pairing it answers every
`pair/start` with `403`, even from the device it's paired with or one claiming that device's ID
or name, re-checks before storing a confirmed pairing, and won't start pairing itself. Only
unpairing on that device makes it pair again. (If one side unpairs, the other must unpair too
before the two can pair again.) A device also never pairs with its own certificate.

Trust is re-checked for long-lived sessions too: unpairing, or turning BubbleDrop or receiving
off, ends a mirror session with that device at once on either side.

### Pairing

The two users compare a 6-digit code shown on both screens (numeric comparison). The
server commits to its nonce before seeing the client's, so a man in the middle who
substitutes certificates can't steer both screens to the same code. Each attempt succeeds
with probability 10⁻⁶. A PIN typed on one side would not work here: without a PAKE, an
interceptor could brute-force a short PIN offline.

Notation: `Fc` and `Fs` are the client and server fingerprints as each side observed them
in the TLS handshake (64-char hex, as UTF-8 bytes). `Nc` and `Ns` are 32 random bytes. `‖`
is concatenation.

```
commitment = SHA-256("bubbledrop-commit-v2" ‖ Fc ‖ Fs ‖ Ns)
code       = big-endian uint32 of SHA-256("bubbledrop-code-v2" ‖ Fc ‖ Fs ‖ Nc ‖ Ns)[0..4]
             mod 1 000 000, zero-padded to 6 digits
```

All four requests below must arrive on connections presenting the same client certificate
(and, from the client's side, pinned to the same server certificate). The server handles
one pairing at a time.

| step | request | body | response |
|------|---------|------|----------|
| 1 | `POST /api/v2/pair/start`   | `{ "sender": DeviceInfo }` | `200 { "commitment" }` · `403` already paired · `409` busy · `503` off |
| 2 | `POST /api/v2/pair/reveal`  | `{ "nonce": Nc }`          | `200 { "nonce": Ns }`. The server now shows the code |
| 3 | client checks `commitment`, shows the code, and waits for its user to confirm | | |
| 4 | `POST /api/v2/pair/confirm` | `{}` | held until the server's user confirms (≤120 s). `200`: paired, `403`: declined |
| – | `POST /api/v2/pair/wait`    | `{}` | held until the server's user answers (≤120 s): `200 { "accepted": bool }`. Sent alongside step 3 so a decline on the server closes the client's code prompt at once; it doesn't count as the client's confirmation. Older servers answer `404`; ignore it |
| – | `POST /api/v2/pair/cancel`  | `{}` | `200`; the server dismisses its prompt |

A user "confirms" the code only by accepting it **and** then authenticating as the device's
owner (fingerprint or Touch ID, falling back to the screen-lock PIN or login password). The
server answers `confirm` and `wait`, and the client sends `confirm`, only after both. Failed
or cancelled authentication never counts as accepting. If the other side declines or cancels, a
device closes its authentication prompt along with the code.

If the commitment check fails, the client cancels and reports possible interference. On
`200` from confirm, both sides store the other's fingerprint (with device ID and name). A
device that re-pairs with a new certificate replaces its old entry.

### `POST /api/v2/ask` (paired clients only)
Body: `{ "sender": DeviceInfo, "files": [FileOffer, …] }` (1–1000 files).

The receiver shows an accept prompt, using the name stored at pairing, and **holds the
request open** until the user decides, for up to 120 s. Senders must use a read timeout of at
least 150 s.

| status | meaning                                     |
|--------|---------------------------------------------|
| 200    | `{ "sessionId": "…" }`, so upload can begin |
| 401    | the client's certificate isn't paired       |
| 403    | declined or timed out                       |
| 409    | receiver busy with another prompt           |
| 503    | receiving is turned off                     |

### `POST /api/v2/upload?sessionId=…&fileId=…` (paired clients only)
Body: the raw file bytes. `Content-Length` must equal the offered `size`. The session is
bound to the client certificate that created it. Response `200 {}` once the file is saved.
Each offered file may be uploaded once, sequentially, in any order.

| status | meaning                                                         |
|--------|-----------------------------------------------------------------|
| 401    | the client's certificate isn't paired                           |
| 404    | unknown session or file, already sent, or another client's session |
| 400    | length mismatch or truncated body                               |

### `POST /api/v2/cancel?sessionId=…`
Drops the session if it belongs to the caller. Response `200 {}`.

### `POST /api/v2/link` (paired clients only)
Body: `{ "sender": DeviceInfo, "url": "https://…" }`. Opens a web page on the receiver, with no
accept prompt (the sender is already paired). Only `http` and `https` URLs are accepted.

Response `200 { "opened": true|false }`: `true` if the default browser was opened, `false` if the
receiver could only show a notification to open it (Android apps can't open a browser while in the
background).

| status | meaning                         |
|--------|---------------------------------|
| 400    | not an http(s) URL              |
| 503    | receiving is turned off         |

### `POST /api/v2/mirror` (paired clients only)
Body: `{ "sender": DeviceInfo }`. Starts showing the sender's screen on the receiver. The receiver
answers `200 {}` **without** `Connection: close` and keeps the connection open; the sender then
streams packets on it until either side closes the connection.

Each packet is a 13-byte big-endian header followed by the payload:

| bytes | field                                                        |
|-------|--------------------------------------------------------------|
| 1     | type (see below)                                             |
| 8     | presentation time in microseconds                            |
| 4     | payload length (≤ 16 MiB)                                    |

Phone → viewer types: `1` codec config, `2` video frame, `3` end of stream, `4` remote control
switched on/off (payload one byte, `1`/`0`).

The request body may include `"control": true` when the phone accepts input (its remote-control
service is on). The viewer then sends type `10` packets the other way, on the same connection: a
UTF-8 JSON input event (≤ 64 KiB). Coordinates are fractions (0–1) of the phone screen, y down.

| event                                                 | meaning                                  |
|-------------------------------------------------------|------------------------------------------|
| `{"t":"tap","x":…,"y":…,"ms":50}`                     | tap; a longer `ms` is a long press       |
| `{"t":"swipe","p":[[x,y],…],"ms":…}`                  | drag along the points over `ms`          |
| `{"t":"key","k":"back"\|"home"\|"recents"\|"enter"\|"backspace"}` | system or editing key |
| `{"t":"text","s":"…"}`                                | type into the focused field              |

The phone ignores input unless a mirror session the user started is running.

Video is H.264 in Annex-B form (start-code separated NAL units), without B-frames. A codec config
packet carries the SPS/PPS and is sent again whenever the picture size changes (e.g. rotation).
Senders keep frames coming while the screen is idle; receivers drop the stream after 15 s of
silence. One mirror session at a time: `409` if the receiver is already showing a screen, `503`
if receiving is off.

## Notifications

A phone with Notification access forwards its notifications to the paired Macs it can see. All
three endpoints are for paired clients only.

### `POST /api/v2/notify` (phone → Mac)
`{ "event": "posted", "notification": PhoneNotification }` or `{ "event": "removed", "key": "…" }`.
Response `200 {}`. Re-posting the same `key` replaces the earlier notification.

```jsonc
// PhoneNotification
{ "key": "0|com.whatsapp|1|…", "package": "com.whatsapp", "app": "WhatsApp",
  "title": "Mom", "text": "Call me back", "subText": "", "time": 1792780000000,
  "category": "msg",                       // Android category; "call" for call notifications
  "actions": [ { "index": 0, "title": "Reply", "reply": true },
               { "index": 1, "title": "Mark as read", "reply": false } ],
  "icon": "iVBORw0…" }                     // app icon, base64 PNG (optional)
```

The phone skips its own notifications, group summaries, ongoing ones other than calls, and
minimum-importance ones. The Mac decides what to show (all notifications, calls, per app).

### `POST /api/v2/notification/action` (Mac → phone)
`{ "key": "…", "index": 0, "reply": "On my way" }` runs that action; `reply` fills the action's
text input. `200 {}`, or `404` if the notification or action is gone.

### `POST /api/v2/notification/dismiss` (Mac → phone)
`{ "key": "…" }` clears the notification on the phone. `200 {}`.

## Calls

Phone calls from the phone's default phone app don't go through `/notify`; they drive a call
screen instead. Call audio stays on the phone: Android doesn't let apps capture or inject call
audio, so the Mac can ask the phone to switch to its speaker. Both endpoints are for paired
clients only.

### `POST /api/v2/call` (phone → Mac)
`{ "id": "…", "state": "ringing" | "active" | "ended", "caller": "Mom", "speaker": false }`.
`id` stays the same for the whole call; `caller` is the contact name or the number; `speaker`
is whether the phone's speaker is on. Response `200 {}`.

While a call is ringing or active the phone repeats its state every 5 seconds, and it sends
`ended` up to three times. The Mac treats a call it hasn't heard about for 15 seconds as ended,
so the call screen closes even if the phone drops off the network mid-call. The phone follows
one call at a time: `ended` from a phone also clears that phone's call under an older `id`.

### `POST /api/v2/call/action` (Mac → phone)
`{ "action": "answer" | "decline" | "hangup" | "speaker", "speaker": true }`. For `answer`,
`speaker: true` switches the phone to speaker once the call connects; for `speaker` it turns the
speaker on or off. `200 {}`, or `404` if there's no call.

## Phone status (desktop widget)

All three endpoints are for paired clients only.

### `POST /api/v2/status` (phone → Mac)
The phone's battery, accessories, connections and sound settings, sent to every paired Mac it can
see whenever one changes and at least once a minute. The Mac treats a phone it hasn't heard from
in 150 seconds as disconnected.

```json
{ "battery": 78, "charging": true, "minutesToFull": 40, "powerSaver": false,
  "bluetoothOn": true,
  "accessories": [{ "name": "Buds", "kind": "headphones", "battery": 64 }],
  "wifi": true, "wifiName": "Home", "wifiBars": 4, "carrier": "Airtel", "cellBars": 3,
  "ringer": "ring", "doNotDisturb": false, "hotspot": true }
```

`kind` is `headphones`, `watch`, `speaker`, `car`, `input` or `other`. Bars are 0–4. `ringer` is
`ring`, `vibrate` or `silent`. `hotspot` is whether the phone's Wi-Fi hotspot is on. Optional fields are left out when unknown (e.g. `wifiName`, which
Android only reveals to apps allowed to use location). Response `200 {}`.

### `POST /api/v2/find` (Mac → phone)
`{}`. The phone rings at full volume, even on silent, for 30 seconds or until stopped on the
phone. `200 {}`.

### `POST /api/v2/mirror/request` (Mac → phone)
`{ "ownerConfirmed": bool }`. Asks the phone to start mirroring to this Mac. The Mac sends
`ownerConfirmed: true` only right after its owner confirmed with Touch ID (or password). Older
Macs send `{}`, which counts as `false`.

If the phone's user turned on "Mirror Without Asking", the phone keeps the screen capture it was
last allowed between sessions (Android's casting indicator stays on), and a request with
`ownerConfirmed: true` starts mirroring at once, with nothing shown on the phone. Otherwise the
phone's owner confirms with a fingerprint, then Android's consent for the whole screen:
directly if BubbleDrop is open, otherwise from a notification. Stopping the kept capture
on the phone, Android ending it, unpairing, or turning the setting off means consent is needed
again. `200 {}` means the request was handled (mirroring started or the offer was shown).

## Folder sync

Each device can pick one folder to keep identical with its paired device. Sync is two-way and
starts within seconds of a change while both are on the same network; changes made while apart
sync when they next see each other. Every device only ever changes **its own** folder: it fetches
the other side's index, decides per file, and pulls what it needs. All three endpoints are for
paired clients only; `503` while receiving is off.

### What is synced

Regular files, by their path relative to the folder (`/`-separated, as stored on disk). Skipped:
symlinks, empty folders, any path component starting with `.` (this also hides BubbleDrop's own
`.bubbledrop-partial-*` downloads and `.bubbledrop-trash/`), names starting with `~$`,
`Thumbs.db`, `desktop.ini`, and names ending in `.tmp`, `.part`, `.crdownload` or `.download`. A
file modified in the last 2 seconds is left until it settles. A path from the other side is
rejected if it is absolute, has an empty, `.` or `..` component, a backslash or a control
character, or is longer than 1024 bytes.

### `POST /api/v2/sync/index`
Body `{}`. Response `200 { "folder": "Notes", "entries": [SyncEntry, …] }`, or `404` if this device
has no sync folder. The device rescans its folder first, so the index is current. JSON responses
here may exceed 1 MiB (up to 64 MiB).

```jsonc
// SyncEntry
{ "path": "Projects/plan.txt", "size": 1234,
  "hash": "…sha-256 hex…",   // the file's content now; null if it was deleted here
  "base": "…sha-256 hex…" }  // the content both devices last agreed on; null if never
```

`base` is what makes deletes and conflicts work without timestamps: a device records a file's
hash as `base` whenever it sees both sides holding the same content, and keeps an entry with
`hash: null` after a delete until the other side has deleted it too.

### `POST /api/v2/sync/file`
Body `{ "path": "…" }`. Response `200` with the raw bytes (`Content-Type:
application/octet-stream`, exact `Content-Length`), or `404` if the path isn't a file in the
index. The puller hashes what it receives and discards it if the hash isn't the one the index
promised (the file changed in between); the next round picks up the new version.

### `POST /api/v2/sync/changed`
Body `{}`. Response `200 {}`. "My folder changed, sync with me soon." Sent after a local change,
and after a sync round that changed anything (so the other side records the new `base`). A
device that receives it syncs after a short delay; rounds that change nothing send nothing, so
the exchange ends.

### Deciding

For each path, with `L`/`Lb` this device's hash and base and `R`/`Rb` the other's (null when
the path is missing from an index):

```
if L == R                         → in sync; set Lb = L (drop the entry if both are null)
remoteNewer = Rb == L && Lb != R  // the other side saw our version, then changed it
localNewer  = Lb == R && Rb != L  // we saw theirs, then changed ours
remoteNewer only                  → take R: pull it, or delete our file if R is null
localNewer only                   → nothing; the other side will pull from us
otherwise (a conflict):
  L is null                       → take R   (an edit beats a delete)
  R is null                       → nothing
  our fingerprint < theirs        → nothing  (the smaller certificate fingerprint keeps the name)
  else                            → rename ours to "name (conflict from <this device> <date>).ext",
                                    then take R; the copy syncs to the other side as a new file
```

Before replacing or deleting a file, a device checks it hasn't changed since it was scanned, and
skips it for this round if it has. Replaced and deleted files go to the Trash (macOS) or
`.bubbledrop-trash/` in the folder (Android, kept 30 days), never straight to deletion. A
device whose folder is missing or unreadable stops syncing rather than reporting its files as
deleted. Picking a different folder starts over with no `base`s, so the first round only adds
files on both sides.

## Receiver rules

- Use only the last path component of `name`; strip leading dots, `/`, `\`
  and `:`; never overwrite (`name (1).ext`, …).
- With a `path`, recreate its folders inside the save folder, each folder name cleaned like a file
  name. A sent folder never merges into an existing one: if `Trip` exists, the whole send goes to
  `Trip (1)`. A `path` that is absolute, or has an empty, `.` or `..` component, a backslash or a
  control character, is ignored and the file is saved like a loose one.
- Write to a temporary file and move it into place only after the full length
  has arrived.
- Sessions expire 10 minutes after the last activity.
