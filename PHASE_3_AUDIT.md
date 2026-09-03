# Phase 3 — Repository Audit

Date: 2026-09-03
Scope: full repository, on the tree containing `master` plus the Phase 2A security core.

This document records what the repository **actually contains**, established by inventory
and targeted search rather than by reading the Phase 2 plan. It exists because the Phase 3
brief assumed a feature set that is largely absent, and a bug hunt aimed at code that does
not exist produces fabricated results.

---

## 1. Method

- Full file inventory via `git ls-tree` on both branches.
- Per-subsystem presence probes with `grep -rilE` over `android/app/src` and `mcp-server/src`.
- Every non-zero hit was opened and classified as a real subsystem or a false positive.
- Reachability checked by searching for references to each component from **outside its own
  package**.

False positives that would otherwise have inflated the result: `Automation` matched
`UiAutomation`/`DeviceAutomator`; `Synchroniz` matched the keyword `synchronized`;
`completion` matched coroutine prose. None of them indicate the subsystem they name.

---

## 2. Inventory

29 Kotlin source files under `android/app/src/main`, 5 TypeScript files under
`mcp-server/src`. One activity, one XML layout, no database, no dependency injection, no
Compose, no ViewModels.

Declared Android dependencies are limited to: `core-ktx`, `appcompat`, `constraintlayout`,
`lifecycle-service`, `lifecycle-runtime-ktx`, Material, coroutines, `kotlinx.serialization`,
and Java-WebSocket. Nothing for persistence, DI, or any AI provider.

---

## 3. Subsystem presence

### Present and reachable from the running app

| Subsystem | Location |
|---|---|
| Accessibility automation | `service/DroidPilotAccessibilityService.kt`, `automation/` |
| Wire protocol | `protocol/Protocol.kt` |
| Authenticated, encrypted transport | `security/SecureChannel.kt`, `AuthGate.kt`, `PairingSecret*.kt` |
| Control server and command dispatch | `server/` |
| Foreground service, UI | `service/ServerForegroundService.kt`, `ui/MainActivity.kt` |
| MCP server | `mcp-server/src/` |

### Present but **not reachable** from the running app

This is the audit's most consequential finding.

| Component | Referenced from outside its own package |
|---|---|
| `AuthorizationManager` | nothing |
| `RootCommandHandler` | nothing |
| `RootManager` | nothing |
| `ProcessShellExecutor` | nothing |
| `RequestGuard` | nothing |
| `AuditLogger` | nothing |
| `RemotePermission` | nothing |
| `PairedDeviceRegistry` | nothing |

The only implementations of `PairedDeviceRegistry` and of `ShellExecutor` as a collaborator
live in **test** sources. `CommandDispatcher` exposes no shell or root command. The security
core is a well-tested library that the application never calls.

This is not a defect in the components — they were delivered as a foundation and documented
as such. It is a defect in any claim that the application enforces the model they implement.
Until a remote-command path exists, statements like "root is authorised before execution"
describe a library, not the shipped app.

### Absent

`ModeManager` / dual mode · agent core · device identity · device-to-device pairing ·
command bus · offline queue · root sessions · root automations · knowledge graph ·
Room/database/migrations · AI provider · context engine · token budgeting · dependency
injection · ViewModels/Compose · APK installation · diagnostics · synchronisation ·
`WorkManager`/background workers · remote UI · developer UI.

---

## 4. Static audit

| Check | Result |
|---|---|
| `TODO` / `FIXME` / `HACK` / `XXX` / `TEMP` / `MOCK` / `STUB` / `PLACEHOLDER` / `NotImplemented` | **none** in `main` or `mcp-server/src` |
| Hardcoded credentials, API keys, tokens, device ids | **none** — the two grep hits are a redaction flag-list and a `SharedPreferences` key name |
| `catch (Throwable)` | none |
| Broad `catch (Exception)` in `main` | 11, each at a boundary with a stated reason |
| Empty/swallowed catch blocks | none |
| Unchecked casts | one, `CommandDispatcher.encode`, annotated `@Suppress("UNCHECKED_CAST")` and guarded by the `is List<*>` branch above it |
| Force unwraps (`!!`) in `main` | none |

Dead configuration: `robolectric` is declared as a test dependency but no test uses it. It
was evaluated and deliberately rejected (its `AccessibilityNodeInfo` shadow returns
`childCount = 0`; `AndroidKeyStore` is unavailable). The declaration is harmless but
misleading and is a candidate for removal.

Stray files: `post_en.txt` and `post_ja.txt` at the repository root are social-media
announcement drafts, unreferenced by any build or document.

---

## 5. Build verification

A clean build from `./gradlew clean` through unit tests, lint on both variants, both APKs
and the instrumented APK completed successfully with no fixes required. See
`PHASE_3_TEST_RESULTS.md`.

---

## 6. What Phase 3 could and could not test

| Brief section | Testable? | Why |
|---|---|---|
| §3 build verification, §4 static audit | Yes | Done |
| §10 command execution, §17–18 authorisation and replay | Partly | Testable at library level only; there is no remote path to exercise |
| §13–14 root authorisation, grants, revocation | Library only | 22 handler tests + 27 authorisation tests; no wired path, and no root provider exists in this environment |
| §43 fuzz / malformed input | Yes | Done against the live dispatcher |
| §5 Pilot Mode regression | Yes | 35 instrumented tests on a real emulator |
| §6–9 dual mode, Developer Mode, iteration limits, pause/resume | **No** | The subsystems do not exist |
| §11–12 remote devices, permission matrix end-to-end | **No** | No remote transport, no device identity |
| §21 offline queue, §22–23 knowledge graph, §24–29 AI | **No** | The subsystems do not exist |
| §30 database and migrations | **No** | There is no database |
| §31–32 lifecycle and Android restrictions | Partly | Instrumented tests exercise service binding and activity launch; no automated coverage of reboot or battery-optimisation paths |
| §35 UI bug hunt | Partly | One activity; no automated UI test beyond the instrumented suite |

Nothing in the "No" rows was tested, and nothing in them is reported as passing.
