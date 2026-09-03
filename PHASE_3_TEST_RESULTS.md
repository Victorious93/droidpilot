# Phase 3 — Test Results

Date: 2026-09-03
All figures below were produced by commands executed in this session. Nothing is estimated.

---

## Build verification

Clean build, from `./gradlew clean` through every verification task:

```
./gradlew clean testDebugUnitTest lintDebug lintRelease \
          assembleDebug assembleRelease assembleDebugAndroidTest --offline
```

| | |
|---|---|
| Result | **BUILD SUCCESSFUL** |
| Duration | 1m 52s (first clean run), 2m 14s (re-run after the rebrand) |
| Lint | clean on `debug` and `release`; `lint.abortOnError` is enabled |
| Compilation fixes required | none |

Artefacts produced:

| Artefact | Size |
|---|---|
| `android/app/build/outputs/apk/debug/app-debug.apk` | 14,581,399 bytes |
| `android/app/build/outputs/apk/release/app-release-unsigned.apk` | 2,748,266 bytes |

The release APK is **unsigned** — no signing credentials are configured in this environment.
It still exercises R8 and resource shrinking, which is where release-only breakage lives,
but it cannot be installed. This is stated rather than glossed: no installation was
attempted, and none is claimed.

---

## Unit tests — Android

`./gradlew testDebugUnitTest`

| Suite | Tests |
|---|---|
| `AuditLoggerTest` | 9 |
| `AuthGateTest` | 13 |
| `AuthorizationManagerTest` | 27 |
| `CommandDispatcherFuzzTest` | 7 |
| `CommandDispatcherTest` | 31 |
| `ControlServerTest` | 15 |
| `ElementSelectorTest` | 10 |
| `PairingSecretTest` | 10 |
| `ProtocolTest` | 11 |
| `RequestGuardTest` | 13 |
| `RootCommandHandlerTest` | 24 |
| `SecureChannelTest` | 17 |
| **Total** | **187 — 0 failures, 0 skipped** |

Phase 3 added 22 tests (165 → 187): 2 for the `AI_ROOT` consumption defect, 2 for audit
accuracy in the root handler, 9 for the audit logger, 2 for the bounded replay guard, and 7
fuzz suites.

---

## Unit tests — MCP server

```
npm run typecheck && npm test && npm run build
```

| | |
|---|---|
| Typecheck | pass |
| Tests | **38 pass, 0 fail, 0 skipped**, 6 suites, 2.77s |
| Build (`tsc`) | pass |

---

## Reproduce-then-fix log

Each defect was demonstrated by a failing test before its fix was written. The four
reproductions, as first observed:

```
AuditLoggerTest > clearing the log is recorded as its own event, not as a revocation FAILED
AuthorizationManagerTest > a single-use AI_ROOT grant is spent after one AI root command FAILED
RootCommandHandlerTest > an empty command is refused and recorded FAILED
RootCommandHandlerTest > a command refused for want of root is not recorded as having executed FAILED
```

After the fixes, the same tests pass and the full suite is green. The bounded-replay-guard
defect was demonstrated separately by its two new tests.

---

## Fuzzing

`CommandDispatcherFuzzTest` — approximately 28,000 dispatches against the live
`CommandDispatcher`:

- 18 commands × 25 parameter names × 30 hostile values (wrong types, `null`, booleans,
  `Long`/`Int` extremes, `1e308`, a 20,000-character string, a NUL character, `../../etc/passwd`,
  `; rm -rf /`, `$(whoami)`, `%s%s%s%n`, objects and arrays where scalars are expected)
- empty parameter objects, unknown parameter names, an empty parameter name,
  `__proto__` and `constructor` keys
- unknown, empty, whitespace, wrong-case, null-byte and 5,000-character command names
- numeric extremes on every coordinate, budget and timeout parameter
- empty selectors on all four selector-shaped commands
- a seeded (fixed-seed, reproducible) random sweep of 2,000 requests

**Result: no exception escaped, and every failure was structured** — an error code plus a
non-empty message, correlated to the request id. No defect found.

Shell-injection and path-traversal strings were supplied as *parameter values*; the
dispatcher passes them to accessibility selectors and never to a shell, so this establishes
robustness, not shell-injection resistance — there is no shell on this path to inject into.

---

## Instrumented tests — not re-run in this session

The 35 instrumented tests (18 accessibility, 9 end-to-end, 8 Android Keystore) last ran on
CI against master's merge commit `69c6e4f`:

```
<testsuite tests="35" failures="0" errors="0" skipped="0" time="76.112">
```

**They have not been run against the Phase 3 changes.** No emulator is possible in this
container — there is no `/dev/kvm`, no `vmx`/`svm` CPU flag, and the emulator refuses arm64
images on an x86_64 host. The instrumented APK compiles (`assembleDebugAndroidTest`
succeeds), and the Phase 3 changes are confined to `core/` components that the instrumented
suite does not exercise plus user-facing strings, but that is reasoning, not evidence. The
emulator job on CI is the verification, and it has not run.

One coupling was checked by hand rather than assumed: three instrumented assertions search
for the visible text `"DroidPilot"`. `ElementSelector` defaults to `exact = false`, so
substring matching means they still match the renamed label `"DroidPilot Forge"`.

---

## Not tested, and why

| Area | Reason |
|---|---|
| Dual-mode switching, Developer Mode, iteration limits, pause/resume/stop | The subsystems do not exist in the repository |
| Two-device pairing, remote command bus, offline queue | No device identity or remote transport exists |
| Knowledge graph, AI provider, context retrieval, token budgets | The subsystems do not exist |
| Database migrations, fresh-install vs upgrade | There is no database |
| Real root execution, root sessions, root provider behaviour | No root provider exists in this environment, and no code path reaches `RootManager` |
| APK installation, launch on a physical device | No device available; installation was not attempted |
| Reboot, battery optimisation, background restrictions | Requires a physical device |

None of the above is reported as passing anywhere in this repository.
