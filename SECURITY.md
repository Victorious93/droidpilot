# Security

DroidPilot lets a program on your network read your screen and act on your device. That is
a large amount of authority, so this document states plainly what protects it, what does
not, and what an attacker can and cannot do.

## Reporting a vulnerability

Open a GitHub security advisory on this repository, or a normal issue if the problem is not
sensitive. Please do not post working exploits against other people's devices.

---

## What DroidPilot can do, and why that matters

While the server is running and a client is paired, that client can:

- read the full accessibility tree of whatever is on screen — including message contents,
  email bodies and page text in other apps;
- capture the screen;
- tap, swipe, type, and press system keys;
- launch any app that has a launcher icon.

That is the same authority a person holding your unlocked phone has. Treat the pairing
secret exactly as you would treat your lock-screen PIN.

Two things are deliberately withheld:

- **Password fields are never transmitted.** Nodes flagged `isPassword` have their text
  stripped before serialisation, and the text a client types is never echoed back in a
  response. Both matter because responses travel over the network and into an AI
  conversation transcript.
- **The server only runs while you have started it.** There is no boot receiver and no
  automatic restart. Closing it from the notification stops it.

---

## Authentication

Every connection must present a 256-bit pairing secret in an `Authorization: Bearer`
header. This is not optional and there is no way to disable it.

**The secret is checked during the HTTP upgrade, before the WebSocket exists.** A rejected
client never reaches a state where it could send a command. This is a correction of a
serious flaw in 1.0, described under *Known issues fixed in 2.0* below.

- Comparison is constant-time (`MessageDigest.isEqual`). A naive `==` would leak the
  secret's prefix to anyone able to time responses, which on a LAN is anyone.
- Failure messages never distinguish "missing" from "incorrect".
- Five failures from one peer host within a minute lock that host out for five minutes.
  The lockout is keyed on host rather than host:port, so reconnecting from a new ephemeral
  port does not reset the budget, and it applies even to a subsequently correct secret.
- The secret is generated with `SecureRandom` on first run and can be regenerated at any
  time from the app. Regenerating stops the server, so sessions authenticated with the old
  secret cannot outlive it.

### Storage at rest

The secret is stored wrapped with AES-256-GCM under a non-exportable `AndroidKeyStore` key.
On devices with a TEE or secure element, that key never enters the app process, so a copy
of the app's data directory cannot be unwrapped off-device. The wrapped blob is excluded
from cloud backup and device transfer, so a restore generates a fresh secret and re-pairs
rather than moving the credential to new hardware.

---

## Transport encryption

Every frame after the handshake is encrypted with AES-256-GCM. The construction is
documented in full in `SecureChannel.kt`; the summary:

| Element | Choice |
|---|---|
| Key schedule | HKDF-SHA256 (RFC 5869) over the pairing secret, with a 16-byte server-chosen salt per session |
| Record cipher | AES-256-GCM, 128-bit tag |
| Nonce | 4-byte direction-specific prefix from the key schedule ‖ 8-byte big-endian record counter |
| Replay defence | Receiver requires strictly increasing counters |
| Direction separation | Distinct keys for client→server and server→client |
| Framing | Binary WebSocket frames: `nonce(12) ‖ ciphertext ‖ tag(16)` |

A fresh salt per connection means session keys differ even though the pairing secret is
long-lived, so a nonce is never reused under a key. Replay rejection is not decoration
here: a record *is* a device command, and a replayed `tap` is a second real tap that AEAD
alone would happily authenticate.

Once the session is established, plaintext frames are rejected and the connection dropped.
Accepting them would be a downgrade path for an attacker who cannot produce valid
ciphertext.

### Why this rather than TLS

The two peers are devices the same person owns, already paired out of band by copying a
secret from the phone's screen. That is a pre-shared-key problem: there is no third party
to vouch for, no name to verify, no revocation story. Self-signed TLS would add certificate
generation, trust-on-first-use, and a fingerprint the user must compare — all to re-derive
a secret they already transferred by hand.

The decisive practical point is testability. This construction is exercised by ordinary
JVM and Node unit tests, including a shared vector both implementations assert byte for
byte, so a change that breaks compatibility fails CI. An `AndroidKeyStore`-backed TLS stack
can only be tested on a physical device, and a security mechanism that cannot be tested in
CI is one that quietly rots.

### What this does not defend against

- **Anyone holding the pairing secret is the legitimate client.** The secret is the entire
  authority.
- **No forward secrecy.** An attacker who records traffic and later obtains the secret can
  decrypt those recordings. Regenerating the secret bounds that exposure; a future protocol
  version adding an ephemeral ECDH exchange would remove it.
- **Traffic analysis.** Frame sizes and timing are not padded, so an observer can infer
  that a screenshot was taken, or roughly how large the UI tree is.
- **A compromised paired client.** DroidPilot authenticates the channel, not the intentions
  of whatever is on the other end.

---

## Network exposure

The server binds `0.0.0.0` by default, so the device is reachable over Wi-Fi, Ethernet or
its own hotspot without the user needing to know which interface applies. That breadth is
defensible *only* because authentication is mandatory and enforced during the handshake.

For a tighter posture, enable **Loopback only** in the app and reach the device through
`adb forward tcp:8765 tcp:8765`. Nothing on the network can then reach the server at all.

Concurrent clients (4), in-flight commands (8) and inbound frame size (1 MiB) are all
capped. This is a server on a phone: without limits a handful of connections can exhaust
its memory, and the failure takes the whole device down with it.

---

## Permissions

| Permission | Why |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Reading the UI tree and dispatching gestures. The core capability. |
| `INTERNET` | Binding a local listening socket. Despite the name, no outbound connection is ever made. |
| `ACCESS_NETWORK_STATE` | Discovering the device's own address to display. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keeping the server alive while the user has started it. |
| `POST_NOTIFICATIONS` | Showing the status notification, which is also how the server is stopped. |

Deliberately **not** requested:

- `QUERY_ALL_PACKAGES` — replaced by a `<queries>` element matching launcher activities.
  That covers exactly the apps `open_app` can launch, without disclosing the user's full
  installed-app list.
- `ACCESS_WIFI_STATE` — addresses come from `ConnectivityManager` instead.
- `canRequestFilterKeyEvents` — DroidPilot has no reason to observe keystrokes, and an
  accessibility service that can read every key press is a keylogger by another name.

`android:networkSecurityConfig` was removed rather than kept. The previous config set
`cleartextTrafficPermitted="true"` for all destinations, but that setting governs the app
as an HTTP *client* and DroidPilot makes no outbound requests at all — it relaxed a platform
default for nothing.

---

## Logging

Server logs record command names and outcomes, never parameters or response bodies. Typed
text is never logged or echoed. `PairingSecretStore` logs only exception *types*, never
messages, because some OEM Keystore implementations echo key material in exception text.

The app UI displays the secret only behind an explicit "Reveal", and the clipboard entry is
flagged `IS_SENSITIVE` so Android 13+ omits it from the paste preview.

---

## Known issues fixed in 2.0

These were present in 1.0. If you are running it, upgrade.

| Issue | Impact |
|---|---|
| **The server was completely unauthenticated.** An `authToken` field existed but was never assigned anywhere — no UI, no storage, no code path set it. Every 1.0 server accepted any client on the network. | Critical. Anyone on the same Wi-Fi could read the screen and control the device. |
| Even had it been set, the check ran in `onOpen` — after the WebSocket was established. `close()` is asynchronous, so already-buffered frames still reached the command handler. | Critical. |
| No transport encryption; screenshots and full UI text crossed the LAN in cleartext. | High. |
| `cleartextTrafficPermitted="true"` for all destinations, for an app that makes no outbound requests. | Medium. |
| `QUERY_ALL_PACKAGES` disclosed the full installed-app list. | Medium. |
| Four concurrent `wait_for_element` calls exhausted a fixed four-thread pool and wedged the server, `ping` included. | High (availability). |
| Gson bypassed Kotlin null-safety via `Unsafe`, so a malformed frame produced NPEs deep in the handler instead of a clean rejection at the edge. | Medium. |

---

## Threat model summary

**Defended:** an unauthenticated attacker on the same network; passive eavesdropping on the
LAN; frame tampering; replay of captured commands; brute-forcing the secret; offline
extraction of the secret from a copied data directory; resource exhaustion from many
connections.

**Not defended:** an attacker who has the pairing secret; a malicious app already running
with accessibility privileges on the same device; anyone with physical access to an
unlocked device; retrospective decryption of recorded traffic after a secret is leaked
(no forward secrecy); traffic analysis.
