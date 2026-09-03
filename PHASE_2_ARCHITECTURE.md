# Phase 2 — Architecture and Audit

This is the Phase A deliverable: what exists today, what the Phase 2 specification assumed
existed, what is not achievable as written, and the order the rest should be built in.

---

## 1. Audit — what actually exists

The specification's §0 says to extend existing abstractions and not to duplicate existing
functionality. That instruction was taken seriously, so the first step was to check what
those abstractions are. Every one of the following was searched for across the Kotlin and
TypeScript sources:

| Assumed by the spec | Files found |
|---|---|
| `AgentCore` | 0 |
| `ModeManager` | 0 |
| AI / LLM / provider integration | 0 |
| `RootManager`, `su`, Magisk, any root path | 0 |
| `ShellManager`, `Runtime.exec`, `ProcessBuilder` | 0 |
| `AutomationEngine`, triggers, automations | 0 |
| Knowledge graph | 0 |
| Room, `@Entity`, `@Dao` | 0 |
| `PermissionManager` | 0 |
| Pairing / device identity | 0 |
| Audit logging | 0 |
| Command bus / router / registry | 0 |
| Plugin architecture | 0 |

**None of it exists.** DroidPilot at the start of Phase 2 is 3,390 lines of Kotlin and 1,039
lines of TypeScript, and it is one thing: a transport.

```
Claude (the agent)  ──MCP/stdio──▶  Node MCP server  ──authenticated WSS──▶  Android device
                                                                              Accessibility
```

The "agent" in DroidPilot is not in DroidPilot. It is the model on the far end of the
socket. There is no on-device intelligence, no automation engine, no scheduler, no root and
no storage beyond one wrapped secret in `SharedPreferences`.

This matters because it changes what Phase 2 *is*. Most of the specification is not an
extension of existing abstractions — there are none to extend — it is a request to build
several new subsystems from nothing. The one genuine exception is remote device control,
where the existing authenticated, encrypted `ControlServer` is most of the transport
already and should be extended rather than replaced.

### What does exist, and is worth building on

| Component | Role | Reuse |
|---|---|---|
| `ControlServer` | Authenticated, encrypted WebSocket server | **Extend** — the remote device channel |
| `SecureChannel` | AES-256-GCM records, replay rejection | **Reuse as-is** |
| `PairingSecret` / `PairingSecretStore` | 256-bit secret, Keystore-wrapped | **Extend** into device identity |
| `AuthGate` | Constant-time auth, per-host lockout | **Reuse as-is** |
| `CommandDispatcher` | Validation, deadlines, error mapping | **Extend** with a permission table |
| `DeviceAutomator` | Device-control seam, already faked in tests | **Reuse** — Developer Mode's device access |
| `Capability` | Honest capability probing | **Extend** to root |

---

## 2. What is not achievable as specified

### Developer Mode cannot build APKs on the device

§51–54 describe running `./gradlew assembleDebug` on-device, installing the resulting APK,
and launching it. This is not possible on Android, for reasons that are structural rather
than a matter of effort:

- **There is no JDK or Gradle on Android.** No `javac`, no `kotlinc`, no Gradle daemon.
  Android runs ART, not a JVM, and ships no compiler toolchain. Nothing an ordinary app can
  do changes this.
- **An app cannot silently install an APK.** `PackageInstaller` shows a user confirmation
  unless the caller is a device owner or root. There is no ADB-to-self.
- **An app cannot use ADB against its own device** without the user enabling wireless
  debugging *and* the app holding the adb keypair — which is a host-side capability.

The specification's §73 gives the correct instruction for exactly this case: detect the
limitation, explain it, implement the strongest practical alternative.

**The alternative is better than the original.** The MCP server already runs on a host that
has the JDK, Gradle, the repository and — usually — ADB. Developer Mode belongs there:

```
Objective ─▶ Developer Mode (MCP server, on the host)
                 │  plan, inspect, edit, ./gradlew assembleDebug
                 │  adb install
                 ▼
             DroidPilot on the device  ──▶  launch, drive the UI, read state
                 │                          (the existing accessibility channel)
                 ▼
             logcat ─▶ filter ─▶ diagnose ─▶ fix ─▶ rebuild
```

Every step of §5's twenty-four is achievable this way, and the device half — launch,
exercise, inspect, detect crashes — is exactly what DroidPilot already does well. The only
thing that changes is *where the build runs*, which was never the interesting part.

### Root is device-side and real, but not universal

Root requires a provider the user has already installed and must approve DroidPilot in.
DroidPilot detects this and reports it honestly; it never claims root it does not have. See
`RootManager`.

---

## 3. What this change implements

The security core — the part everything else depends on, and the part where a mistake is
not recoverable. A knowledge graph with a bug shows wrong nodes; an authorisation bug hands
a stranger a root shell on someone's phone.

| Component | Purpose |
|---|---|
| `RemotePermission` | The ten permissions plus `AI_ROOT`, in one enum |
| `TrustLevel` | Presets that expand into grants and are never consulted at execution time |
| `Grant` / `GrantDuration` | Once / timed / until-revoked, with expiry and revocation |
| `AuthorizationManager` | The single decision point. No cache, no bypass, no second path |
| `RequestGuard` | Request-level replay and clock-skew rejection |
| `RootManager` | Provider-agnostic root detection and elevated execution |
| `ProcessShellExecutor` | Concurrent stream draining, capped output, forcible timeout |
| `RootCommandHandler` | The ordered check sequence from §17 |
| `AuditLogger` | Structured trail; sizes not contents; flag redaction |

**58 new tests**, all passing; 164 unit tests and 35 instrumented tests in total.

### The security boundary

A privileged request is allowed only when *all* of these hold, re-evaluated on every
command:

1. the transport authenticated the peer (existing `AuthGate`, during the HTTP upgrade);
2. the request is fresh and not replayed (`RequestGuard`);
3. the device is paired;
4. a grant exists for that device and permission;
5. it is not revoked;
6. it has not expired;
7. a single-use grant has not been spent;
8. and for AI-initiated root, `AI_ROOT` is separately granted.

Pairing is step 3 of eight. It confers nothing on its own, and no trust-level preset
includes `REMOTE_ROOT` or `AI_ROOT`.

### Two decisions worth stating plainly

**There is no dangerous-command blocklist**, per §68. Once the owner has explicitly
authorised root for a device, root commands from it are the feature working. A blocklist
would refuse legitimate administration while stopping nobody who can write the same thing
another way — and its worst effect would be inviting the belief that the list is the
boundary. The boundary is owner authorisation, device identity and a live grant.

**Command output is never written to the audit log**, only its size. A root command's stdout
routinely contains the exact material that must not be copied into a log the owner may
later export. Sizes keep the forensic value without making a second copy of the secret.
Command *text* is recorded, because a log that cannot say what ran is not an audit log;
arguments to conventionally secret-bearing flags are redacted.

---

## 4. What remains, in order

Each depends on the one before it. The ordering is not the specification's, because the
specification's ordering assumes infrastructure that does not exist.

| # | Phase | Depends on | Notes |
|---|---|---|---|
| 1 | **Device identity** — Keystore keypair, stable id, signed requests | this change | Extends `PairingSecretStore` |
| 2 | **Pairing** — request, owner confirmation, revocation, device list | 1 | |
| 3 | **Command bus** — typed registry, per-command permission, offline queue | 1–2 | Extends `CommandDispatcher`, does not replace it |
| 4 | **Remote device control** — device-as-client, remote UI | 3 | Extends `ControlServer` |
| 5 | **Root UI** — grant prompt, timed grants, revocation screen, terminal | 4 | Engine already built here |
| 6 | **Knowledge graph** — Room model, repository, search, graph UI | independent | Largest single piece |
| 7 | **Context engine** — retrieval, ranking, dedup, budgets, inspector | 6 | Token measurement is only meaningful once there is a corpus |
| 8 | **AI provider abstraction** — providers, routing, structured output | 7 | |
| 9 | **Mode manager** — Pilot / Developer, UI and commands | 8 | Trivial alone; needs both modes to exist first |
| 10 | **Developer Mode** — host-side loop per §2 above | 9 | Lives in the MCP server, not the APK |

Attempting several of these at once would produce placeholder UI over unimplemented
behaviour, which §73 forbids and which is worse than an honest gap: a button that does
nothing still looks like a finished feature in a screenshot.

---

## 5. Known limitations

- **Grants are in-memory.** `InMemoryGrantStore` is the only implementation; a
  Keystore-backed persistent store is the first task of the next phase. Authorisations do
  not survive a process restart yet.
- **Root execution is untested against a real root provider.** The logic, the refusal
  paths and the capability probe are covered by 22 tests against a fake executor. Nothing
  in this environment — and no emulator image — has a root provider to test against, so
  `ProcessShellExecutor`'s elevated path is verified by construction, not by execution.
  This is the most important thing to verify on a real rooted device.
- **No UI yet** for pairing, permissions or root authorisation. The engine is complete and
  tested; the screens are phase 5.
- **`AI_ROOT` has no AI to gate** until phase 8. The permission and its enforcement exist
  and are tested; the subsystem it constrains does not exist yet.
