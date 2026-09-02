# Architecture

## What DroidPilot is

DroidPilot is a **transport**, not an automation engine.

It exposes one Android device to an external AI agent over MCP. The intelligence lives in
the agent — Claude Desktop, Claude Code, or any MCP client. DroidPilot's job is to make the
device's screen readable and its UI actionable, reliably and safely, and then get out of the
way.

That framing decides most of the design. There is no on-device rule engine, no scheduler and
no scripting language, because the thing on the other end of the socket already is one. What
DroidPilot owes that agent is a truthful view of the screen, actions that either happen or
report why they did not, and a channel nobody else can use.

```
┌──────────────┐   MCP / stdio   ┌──────────────┐   WebSocket    ┌─────────────────────┐
│  AI agent    │ ◄─────────────► │  MCP server  │ ◄────────────► │  Android device     │
│  (Claude, …) │                 │  (Node/TS)   │  authenticated │  Accessibility svc  │
└──────────────┘                 └──────────────┘   + encrypted  │  + control server   │
                                                                 └─────────────────────┘
```

---

## Android module

Single Gradle module, layered by package. The layering is enforced by dependency direction:
nothing below the service layer imports Android UI types, and nothing above the automation
layer touches `AccessibilityNodeInfo`.

```
com.mobilemcp.pro
├── core/          OperationResult, ErrorCode, Capability, NetworkAddresses
├── protocol/      Wire types (kotlinx.serialization)
├── security/      SecureChannel, PairingSecret, PairingSecretStore, AuthGate
├── automation/    DeviceAutomator interface, UiNode, ElementSelector, AutomatorRegistry
├── server/        ControlServer, CommandDispatcher, CommandParams, ServerController
├── service/       DroidPilotAccessibilityService, ServerForegroundService
└── ui/            MainActivity
```

### The seam that matters

`DeviceAutomator` is the boundary between "what DroidPilot can ask of a device" and "how
Android does it". Everything above it works with immutable `UiNode` snapshots; only
`DroidPilotAccessibilityService` touches live nodes.

This is not architecture for its own sake. An `AccessibilityService` can only run on a real
device, so without this seam the dispatcher — parameter validation, deadlines, capability
checks, error mapping, the code with by far the most branches and the most opportunity to be
wrong — could only be tested by hand on a phone. With it, `CommandDispatcher` has 31 unit
tests running in milliseconds against a fake.

Keeping live nodes from escaping matters for a second reason: an `AccessibilityNodeInfo` is
valid only for the window generation that produced it, and one held across a screen change
silently starts returning stale data.

### Who owns the server

`ServerForegroundService` owns `ControlServer` for exactly as long as the server should
live. `MainActivity` owns nothing; it sends intents and renders `ServerController`'s flows.

In 1.0 this was inverted — the Activity constructed and held the WebSocket server while the
service only posted a notification. Because an Activity is destroyed on every configuration
change, **rotating the screen killed the server**, and the notification carried on claiming
otherwise because it had never been connected to anything. Inverting the ownership is what
makes rotation, backgrounding and process death uneventful: there is no state to save
because the Activity holds none.

### Concurrency

Everything suspends; nothing blocks.

`ControlServer` dispatches each command into a `CoroutineScope` bounded by a `Semaphore` —
a *ceiling on concurrent work*, not a thread pool. A command waiting on a gesture callback
or polling for an element is suspended, occupying no thread.

1.0 used a fixed pool of four threads and let `wait_for_element` block one with
`Thread.sleep` for its entire caller-chosen timeout. Four concurrent waits filled the pool
with sleeping threads and wedged the server completely, `ping` included.
`ControlServerTest` has a regression test that issues six 30-second waits and asserts a
`ping` still comes back.

Platform callback APIs (`dispatchGesture`, `takeScreenshot`) are wrapped with
`suspendCancellableCoroutine`, so an abandoned request unwinds instead of leaking a latch.

### Errors

Every fallible operation returns `OperationResult`, never `null` and never a thrown
exception for an expected failure. `ErrorCode` is a stable, machine-readable taxonomy that
crosses the wire, so a client can distinguish "element not found, try another selector" from
"this device cannot take screenshots, stop asking". Unexpected throwables are caught at the
dispatcher, logged locally with a stack trace, and reported to the peer as a summary only.

### Capabilities, not assumptions

Android grants a shifting subset of what an app requests: an OS version withholds an API, a
user declines a permission, device policy disables a global action, a work profile blocks
screenshots. Code that assumes a capability fails at the point of use, usually as an opaque
"action failed" far from the cause.

`Capability` is probed once when the service connects — from the live
`AccessibilityServiceInfo` rather than the XML config, because the platform may withhold a
requested flag while the XML still claims it — and reported to the client in the handshake,
each unavailable one with a reason.

`NOTIFICATION_ACCESS` is permanently reported unavailable, because reading notifications
needs a `NotificationListenerService` this build does not ship. 1.0 approximated it by
opening the notification shade, sleeping 500 ms, scraping every `FrameLayout` it could see
and pressing Back — producing unlabelled junk while stealing focus from whatever the user
was doing, and wired to no MCP tool. It was removed. A capability honestly reported missing
is more useful than one that appears to work.

---

## MCP server (Node / TypeScript)

```
src/
├── index.ts           stdio entry point
├── server.ts          MCP tool definitions and dispatch
├── android-client.ts  transport to the device
├── tools.ts           tool schemas and descriptions
└── secure-channel.ts  client half of the encrypted channel
```

`AndroidClient` deliberately does **not** extend `EventEmitter`. The 1.0 client did, and
called `this.emit("error", …)` from the socket's error handler — but Node *throws* when
`"error"` is emitted with no listener registered, and nothing ever registered one. A phone
dropping off Wi-Fi threw inside a socket callback, escaped as an uncaught exception, and
killed the entire MCP server process, taking every tool in the user's session with it.
Errors are now ordinary values: a rejected promise for the caller, an optional callback for
state changes. There is a regression test that drops a connection mid-command and asserts
no uncaught exception occurs.

Tool descriptions are written for the model that reads them. The most load-bearing line in
`tools.ts` is the steer from `screenshot` toward `get_ui_tree`: a UI dump is structured,
searchable and roughly two orders of magnitude cheaper in tokens, and a model offered both
will otherwise reach for the picture.

---

## Wire protocol

Version 2. `Protocol.VERSION` and `CLIENT_PROTOCOL_VERSION` must match; a mismatch is
reported by name at handshake rather than failing mysteriously later.

1. Client connects with `Authorization: Bearer <secret>`.
2. Server validates **during the HTTP upgrade** and rejects by throwing from
   `onWebsocketHandshakeReceivedAsServer`. Java-WebSocket answers a refused handshake with
   `HTTP/1.1 404 WebSocket Upgrade Failure` (verified against the library, and asserted by
   the Node test fake), which the client translates into a message naming the pairing
   secret — otherwise the most common setup mistake surfaces as "Unexpected server response:
   404" and sends people debugging the wrong thing.
3. Server sends a 16-byte session salt in the one plaintext frame of the protocol.
4. Both derive session keys via HKDF; every subsequent frame is AES-256-GCM.
5. Server sends an encrypted `hello` with protocol version, app version and capabilities.
6. Commands and responses flow as encrypted binary frames.

Serialization is kotlinx.serialization, not Gson. Gson constructs objects through
`sun.misc.Unsafe`, so a request missing `command` produced a `CommandRequest` whose non-null
`String` field held `null` — Kotlin's type system said it could not happen, and the eventual
NPE surfaced three layers from the malformed input. kotlinx.serialization rejects it at the
edge.

See `SECURITY.md` for the cryptographic construction and threat model.

---

## Testing

144 tests, none requiring a device.

| Suite | Tests | Covers |
|---|---|---|
| `SecureChannelTest` | 17 | AEAD round-trip, replay, reorder, tampering, direction separation, RFC 5869 vector, cross-implementation vector |
| `CommandDispatcherTest` | 31 | Validation, clamping, defaults, deadlines, capability gating, error mapping |
| `ControlServerTest` | 14 | Full connection lifecycle over a real loopback socket |
| `AuthGateTest` | 13 | Authentication, lockout, windowing, per-peer isolation |
| `ProtocolTest` | 11 | Serialization, required-field rejection, forward compatibility |
| `PairingSecretTest` | 10 | Encoding, constant-time comparison, fingerprints |
| `ElementSelectorTest` | 10 | Matching semantics |
| Node suites | 38 | Channel, pairing URIs, and the client against a fake device over a real socket |

Two are worth singling out.

**The cross-implementation vector.** The first record of a session is fully determined by
`(secret, salt, plaintext)`, so both `SecureChannelTest.kt` and `secure-channel.test.ts`
assert the *same* ciphertext bytes. If either implementation changes its key schedule, nonce
layout or framing, CI fails instead of the two silently failing to talk to each other on a
user's device.

**`ControlServerTest`.** It runs the real server on a real socket and immediately paid for
itself: it caught a bug where the custom `Draft_6455` used to cap inbound frame size was
constructed with an empty protocol list. Java-WebSocket's `containsRequestedProtocol`
iterates that list, so with it empty the loop never runs and returns `NOT_MATCHED` — the
server rejected **every** client regardless of credentials. Nothing else would have found
it: the Node tests run against a fake device, and the unit tests never open a socket.

---

## Deliberate decisions

**The `com.mobilemcp.pro` package and applicationId are kept**, despite the product being
called DroidPilot. Changing `applicationId` forces every existing user to uninstall,
reinstall and re-grant accessibility; renaming the service class alone breaks the
accessibility grant. Cosmetic consistency is not worth that. All user-visible naming is
DroidPilot. Revisit only alongside a migration note.

**Single module, not multi-module.** Splitting modules while simultaneously rewriting the
internals would have doubled the risk for no present benefit — package-level layering
already enforces the dependency direction, and the build is fast. Worth revisiting when a
second app or a shared library appears.

**XML layouts with ViewBinding, not Compose.** The UI is one screen. Rewriting it would have
consumed effort that went into the security and concurrency defects instead, and it is the
part of the codebase least likely to contain a bug that matters. A genuine candidate for the
next change, and cheap to do once because nothing else depends on it.

**SharedPreferences, not DataStore**, for the pairing secret. One small blob read once at
server start. DataStore's asynchronous API earns its keep for frequent or large writes and
buys nothing here.

---

## Known limitations

- **No instrumented tests.** Everything touching `AccessibilityService` — node walking,
  gesture dispatch, screenshot capture, capability probing — is verified by reading, not by
  running. This is the largest gap in the project. It needs a device or an emulator in CI.
- **No forward secrecy.** See `SECURITY.md`.
- **No notification access.** Needs a `NotificationListenerService`.
- **Single device per MCP server instance.**

## Where this goes next

The layering makes each of these a local change rather than a rewrite:

- **Instrumented tests** on an emulator in CI — the highest-value next step by a distance.
- **`NotificationListenerService`**, turning `NOTIFICATION_ACCESS` into a real capability.
- **Ephemeral ECDH** at handshake, adding forward secrecy without changing the framing.
- **Compose UI**, isolated behind `ServerController` today.
- **Multiple devices** per MCP server — `AndroidClient` is already per-connection; only the
  single `client` variable in `server.ts` assumes one.
