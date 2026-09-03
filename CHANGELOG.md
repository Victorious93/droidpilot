# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **35 instrumented tests**, closing the project's largest verification gap. Everything
  touching `AccessibilityService` and the Android Keystore was previously verified by
  reading the code, not by running it.
  - `AccessibilityAutomatorInstrumentedTest` (18) — node walking, selectors, gesture
    dispatch, global actions, screen capture, node budgeting and truncation, wait/timeout.
  - `EndToEndInstrumentedTest` (9) — the whole stack on a device: handshake, authentication
    rejection, replay rejection, and commands returning real screen data.
  - `PairingSecretStoreInstrumentedTest` (8) — the real Android Keystore round trip, and
    that the stored form is not plaintext.
- A GitHub Actions job running the instrumented suite on an API 30 emulator, so the
  Accessibility layer is checked on every push rather than only by inspection.
- `AccessibilityServiceHarness`, which enables the service from inside the test process via
  `UiAutomation` shell privileges. Doing it there rather than in a CI step matters: a later
  `installDebug` would undo a setting written outside the test.

### Notes

Robolectric was evaluated for this and rejected. It reports `KeyStoreException:
AndroidKeyStore not found`, and its `AccessibilityNodeInfo` shadow returns `childCount = 0`
and `text = null` — so tests written against it would assert on fixtures while appearing to
cover the riskiest code in the project. Coverage that is not real is worse than none.

## [2.0.0] — 2026-09-02

A security and reliability release. **The wire protocol is not backward compatible**: a 1.0
MCP server cannot talk to a 2.0 device, and vice versa. Update both.

`applicationId` is unchanged, so this installs over 1.0 and the Accessibility grant is
preserved.

### Security

- **Connections now require a 256-bit pairing secret.** In 1.0 an `authToken` field existed
  but was never assigned anywhere — no UI, no storage, no code path set it — so **every 1.0
  server accepted any client on the network with no credential at all**. Anyone on the same
  Wi-Fi could read the screen and control the device.
- **Authentication is enforced during the HTTP upgrade**, before the WebSocket exists. 1.0
  checked in `onOpen`, after the connection was established; because `close()` is
  asynchronous, frames the client had already sent still reached the command handler.
- **All traffic after the handshake is encrypted** with AES-256-GCM, keyed by HKDF-SHA256
  over the pairing secret and a per-session salt, with replay rejection and direction
  separation. 1.0 sent screenshots and full UI text over the LAN in cleartext. See
  `SECURITY.md`.
- Pairing secret stored wrapped under a non-exportable Android Keystore key, and excluded
  from cloud backup and device transfer.
- Constant-time secret comparison; repeated failures from one peer host trigger a lockout
  that survives a subsequently correct secret.
- Password-field text is never transmitted; typed text is never echoed back or logged.
- Replaced `QUERY_ALL_PACKAGES` with a `<queries>` element matching launcher activities —
  same functionality for `open_app`, without disclosing the full installed-app list.
- Removed `android:networkSecurityConfig`, which set `cleartextTrafficPermitted="true"` for
  all destinations in an app that makes no outbound HTTP requests.
- Dropped `ACCESS_WIFI_STATE`; addresses now come from `ConnectivityManager`.
- Narrowed the accessibility event subscription from `typeAllMask` to the two window-change
  types actually needed.
- Added a **Loopback only** mode for use with `adb forward`.

### Fixed

- **Rotating the screen no longer kills the server.** `MainActivity` owned the WebSocket
  server in 1.0, so every configuration change destroyed it — while the foreground service
  notification, which owned nothing, carried on reporting it as running. The service now
  owns the server.
- **Concurrent waits no longer wedge the server.** `wait_for_element` blocked one of four
  fixed pool threads with `Thread.sleep` for its entire caller-chosen timeout, so four
  concurrent waits stalled every request including `ping`. All operations now suspend.
- **A dropped connection no longer kills the MCP server process.** `AndroidClient` extended
  `EventEmitter` and emitted `"error"` with no listener registered, which Node turns into a
  throw; it escaped a socket callback as an uncaught exception. The client no longer uses
  `EventEmitter` for errors.
- **MCP tool failures now set `isError`.** Every failure previously came back as a
  successful call whose text began "Error:", so a model could not distinguish a failed tap
  from a completed one.
- Fixed a data race on the shared accessibility-service reference (a plain mutable static
  written from the main thread and read from socket threads).
- Replaced Gson with kotlinx.serialization. Gson constructs objects via `sun.misc.Unsafe`,
  so a request missing `command` produced a non-null Kotlin `String` field holding `null`
  and an NPE far from the malformed input.
- Malformed parameters now produce a description of the problem. `{"x": "500"}` previously
  surfaced as "Internal error: null".
- `click_element` with no criteria is now rejected. It previously matched the root node and
  reported a successful click on something the caller never asked for.
- Screenshot failures now explain themselves; Android's ~1/second rate limit is reported as
  a retryable condition rather than a generic failure.
- Device addresses are found via `ConnectivityManager` and interface enumeration, so
  Ethernet, USB tethering and hotspot addresses are shown. `WifiManager.getIpAddress()`
  reported "Not connected to WiFi" on reachable devices.
- `startForeground` now uses `ServiceCompat` with a service type and handles
  `ForegroundServiceStartNotAllowedException` instead of crashing.
- `POST_NOTIFICATIONS` is now requested at runtime; on Android 13+ the status notification
  was silently hidden.
- Added the Android 14 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration required for the
  `specialUse` foreground service type.
- Every command now carries a deadline, so a hung platform call fails cleanly.
- Screenshots are size-capped and downscaled rather than buffered without limit.
- Concurrent clients, in-flight commands and inbound frame size are all bounded.
- Fixed a nested-scrolling conflict that made the in-app log pane hard to drag.

### Removed

- **The `notifications` command.** It opened the notification shade, slept 500 ms, scraped
  every `FrameLayout` it could find and pressed Back — returning unlabelled junk while
  stealing focus from whatever the user was doing, and it was wired to no MCP tool. Real
  notification access needs a `NotificationListenerService`, which this build does not ship;
  the capability is now honestly reported as unavailable with that reason.

### Added

- **Capability reporting.** The device probes what it actually grants — gestures,
  screenshots, lock screen, app launch — and tells the client in the handshake, with a
  reason for each unavailable one.
- `long_click_element` for context menus.
- `exact` matching on selectors, so `"OK"` need not match `"NOT OK"`.
- `maxNodes` and a `truncated` flag on `get_ui_tree`, so a partial tree is visible as such.
- `maxDimension` on `screenshot`.
- Structured `ErrorCode` values on the wire, so clients can distinguish retryable failures
  from permanent ones.
- Pairing URI (`droidpilot://host:port#secret`) — one value to copy instead of three fields.
- Protocol version negotiation, reported by name on mismatch.
- **144 tests**, none requiring a device, including a cross-implementation cryptographic
  vector asserted byte-for-byte by both the Kotlin and Node suites.
- GitHub Actions CI: build, test, lint and APK artefacts for both components.
- `ARCHITECTURE.md`, `SECURITY.md`, `CONTRIBUTING.md` and this changelog.

### Changed

- Toolchain: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, `compileSdk`/`targetSdk` 35 (from AGP
  8.2.2, Kotlin 1.9.22, SDK 34). Google Play requires target 35 for new releases.
- Dependencies are managed through a Gradle version catalog.
- Release builds are signed from environment variables or a git-ignored
  `keystore.properties`, and enable resource shrinking alongside R8.
- App display name is now DroidPilot throughout; 1.0 showed "Mobile MCP Pro" on the
  accessibility consent screen the README told users to look for.
- The MCP tool descriptions steer explicitly from `screenshot` toward `get_ui_tree`.
- The Accessibility consent description now states plainly that the service accepts remote
  commands while the server is running.

## [1.0.0]

Initial release.
