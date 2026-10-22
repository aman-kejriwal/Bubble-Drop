# MyDrop protocol v2

An AirDrop-style flow (Discover → Pair → Ask → Upload), rebuilt on transports that both
macOS and Android support. Apple's AirDrop runs over AWDL with Apple ID certificates;
MyDrop runs over the shared Wi-Fi/LAN with self-signed certificates and one-time pairing.

## Identity

Each device has a long-lived P-256 key and a self-signed X.509 certificate (macOS: a PKCS#12
file in `~/Library/Application Support/MyDrop`; Android: the Android Keystore). A device is
identified by its **fingerprint**: lowercase hex SHA-256 of the certificate's DER bytes.

## Discovery

Each receiver advertises a DNS-SD (Bonjour / NSD) service:

- Type: `_mydrop._tcp`
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
{ "id": "…uuid…", "name": "photo.jpg", "size": 123456, "mimeType": "image/jpeg" }
```

Errors return `{ "error": "message" }` with a non-2xx status.

## Endpoints

### `POST /api/v2/discover`
Body: the sender's `DeviceInfo`. Response `200`:
`{ "device": DeviceInfo, "paired": bool }`, where `paired` says whether the server trusts
the client's certificate.

The client may skip pairing only if `paired` is true **and** it trusts the server's
fingerprint itself. Otherwise it pairs first.

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
commitment = SHA-256("mydrop-commit-v2" ‖ Fc ‖ Fs ‖ Ns)
code       = big-endian uint32 of SHA-256("mydrop-code-v2" ‖ Fc ‖ Fs ‖ Nc ‖ Ns)[0..4]
             mod 1 000 000, zero-padded to 6 digits
```

All four requests below must arrive on connections presenting the same client certificate
(and, from the client's side, pinned to the same server certificate). The server handles
one pairing at a time.

| step | request | body | response |
|------|---------|------|----------|
| 1 | `POST /api/v2/pair/start`   | `{ "sender": DeviceInfo }` | `200 { "commitment" }` · `409` busy · `503` off |
| 2 | `POST /api/v2/pair/reveal`  | `{ "nonce": Nc }`          | `200 { "nonce": Ns }`. The server now shows the code |
| 3 | client checks `commitment`, shows the code, and waits for its user to confirm | | |
| 4 | `POST /api/v2/pair/confirm` | `{}` | held until the server's user confirms (≤120 s). `200`: paired, `403`: declined |
| – | `POST /api/v2/pair/cancel`  | `{}` | `200`; the server dismisses its prompt |

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

## Receiver rules

- Use only the last path component of `name`; strip leading dots, `/`, `\`
  and `:`; never overwrite (`name (1).ext`, …).
- Write to a temporary file and move it into place only after the full length
  has arrived.
- Sessions expire 10 minutes after the last activity.
