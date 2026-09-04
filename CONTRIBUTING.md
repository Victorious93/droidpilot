# Contributing

## Prerequisites

- **JDK 17** (the build targets 17; newer JDKs work for running Gradle)
- **Android SDK** with API 35 platform and build-tools 35.0.0
- **Node 20+**

Point Gradle at your SDK with `android/local.properties` (git-ignored):

```properties
sdk.dir=/path/to/Android/sdk
```

## Building and testing

```bash
# Android
cd android
./gradlew testDebugUnitTest     # 252 unit tests, no device needed
./gradlew lintDebug lintRelease # lint; abortOnError is on
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleRelease         # release AAB

# Instrumented tests — needs a connected device or a running emulator.
# These cover the Accessibility layer and the real Android Keystore, which have no
# honest off-device substitute. CI runs them on an API 30 emulator.
./gradlew connectedDebugAndroidTest

# Compiles the instrumented test APK without needing a device — worth running before
# pushing, since it catches every compile error in the suite up front.
./gradlew assembleDebugAndroidTest

# MCP server
cd mcp-server
npm ci
npm run typecheck               # sources and tests
npm test                        # 38 tests
npm run build
```

Run all of it before opening a pull request. CI runs exactly these commands.

## Release signing

Release builds are signed from credentials supplied at build time. **Never commit a
keystore or its passwords** — `*.jks`, `*.keystore` and `keystore.properties` are
git-ignored.

Environment variables (preferred, and what CI would use):

```bash
export DROIDPILOT_KEYSTORE=/path/to/release.jks
export DROIDPILOT_KEYSTORE_PASSWORD=…
export DROIDPILOT_KEY_ALIAS=…
export DROIDPILOT_KEY_PASSWORD=…
./gradlew assembleRelease
```

Or `android/keystore.properties` for local development:

```properties
storeFile=/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

With neither present the release variant still builds, unsigned. That is deliberate: a fork
without credentials should still get a full lint, test and R8 signal from CI rather than a
hard failure.

## Dependencies

Versions live in `android/gradle/libs.versions.toml`. They are pinned deliberately — please
raise a version bump as its own change with a reason, rather than folding it into a feature.

Android Lint's `AndroidGradlePluginVersion` and `GradleDependency` checks are disabled for
this reason; AGP 9.x in particular is a major release with breaking changes, so an automated
nag to jump to it is noise. `ObsoleteSdkInt` is also disabled: it suggests dropping the
`-v26` qualifier from `mipmap-anydpi-v26`, and following that advice fails resource linking
outright because AAPT2 does not accept a bare `mipmap-anydpi` directory.

## Conventions

**Errors are values.** Anything that can fail returns `OperationResult`, never `null` and
never a thrown exception for an expected failure. Add to `ErrorCode` rather than repurposing
an existing entry — those names cross the wire and the MCP server branches on them.

**Nothing blocks.** Use `suspend` functions and `delay`. `Thread.sleep`, `CountDownLatch`
and blocking `await` in request-handling paths are how the 1.0 server wedged itself under
four concurrent waits. Wrap platform callback APIs with `suspendCancellableCoroutine`.

**Keep Android at the edges.** Code below `service/` should not import Android UI types, and
only `DroidPilotAccessibilityService` should touch `AccessibilityNodeInfo`. This is what
keeps the dispatcher testable without a device.

**Never log or echo user content.** Command names and outcomes only — no parameters, no
response bodies, no typed text. Responses travel over the network and into an AI transcript.

**Comment the "why".** Explain a non-obvious decision, a platform quirk, or a bug being
prevented. Do not restate what the code says.

## Testing expectations

New behaviour needs a test. Anything security-relevant — authentication, the secure channel,
input validation — needs tests for the failure paths, not just the happy one.

Two suites deserve care when touched:

- **`SecureChannelTest` / `secure-channel.test.ts`** share a cross-implementation vector:
  both assert the same ciphertext bytes for a fixed `(secret, salt, plaintext)`. If you
  change the key schedule, nonce layout or record framing, both must change together and
  the vector must be regenerated. Do not "fix" one side to match the other without
  understanding which is correct.
- **`ControlServerTest`** runs the real server over a real loopback socket. It exists
  because unit tests and the Node fake device between them missed a bug that made the
  server reject every client. Keep it running against the real thing.

Prefer off-device tests: use `FakeDeviceAutomator` for the Android side and `FakeDevice` for
the Node side. They run in seconds and on every change.

Put a test in `androidTest/` only when it genuinely needs a real system — the Accessibility
layer, gesture dispatch, screen capture, and the Android Keystore all do. Do **not** reach
for Robolectric to fake those: its Keystore is absent and its `AccessibilityNodeInfo`
shadow returns empty values, so such a test asserts on fixtures while looking like coverage
of the riskiest code in the project.

Instrumented tests must fail loudly when their preconditions are unmet. A test that skips
itself because the Accessibility service did not connect reports coverage that does not
exist, which is worse than having no test at all.

## Adding a command

Adding an MCP tool touches five places, in this order:

1. `DeviceAutomator` — the interface method.
2. `DroidPilotAccessibilityService` — the Android implementation.
3. `FakeDeviceAutomator` — the test double.
4. `CommandDispatcher` — validation, deadline and result encoding, plus tests.
5. `tools.ts` and `server.ts` — the schema and MCP wiring.

Write the tool description for the model that will read it: say when to reach for the tool
and what it costs, not just what it does.

## Pull requests

Explain what changed and why. If you fixed a bug, describe the failure it caused — that is
what makes the change reviewable and the test meaningful.

Do not commit secrets, keystores, `local.properties`, or generated `dist/` and `build/`
output.

## On the OpenDroid comparison

This project was audited against [OpenDroid](https://github.com/Victorious93/opendroid), a
larger, separate Android AI-agent app with an on-device LLM-driven agent loop, provider
abstraction (OpenAI/Claude/Gemini/Ollama/local models), tiered memory with a knowledge
graph, vision fallback, voice (STT/TTS/wake-word), a `NotificationListenerService`, and a
habit-based routines engine — none of which exist in this repository. Two of those,
Pilot/Developer-Agent mode switching and a standardised, owner-visible execution history,
were ported in the shape that fits this project's architecture (the agent is the *external*
MCP client, not an on-device model — see [How it works](README.md#how-it-works)). The rest
were deliberately deferred rather than partially stubbed: each is a substantial subsystem
in its own right (an LLM provider abstraction and credential store; a Room-backed memory
architecture with Keystore-backed encryption for the sensitive tier; a vision pipeline;
voice; a `NotificationListenerService`; a scheduler for routines), and none is required for
the security-critical work — authorisation, root, MCP, and now mode/execution reporting —
this project treats as load-bearing. If you want to pick one up, open an issue describing
which piece and how it should plug into the existing authorisation core before writing code,
since bypassing `AuthorizationManager` for a new capability is the mistake this project
exists to not make.
