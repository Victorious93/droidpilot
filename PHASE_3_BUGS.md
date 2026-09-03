# Phase 3 — Bug Register

Every entry was reproduced by an executed test **before** any fix was written. No entry is
recorded on the strength of reading the code alone.

Severity: **P0** privilege escalation / auth bypass / credential leak · **P1** crashes,
broken authorisation, data corruption · **P2** incorrect state, wrong records, resource
growth · **P3** cosmetic or latent.

Status: `OPEN` · `FIXED` (change made, targeted test passes) · `VERIFIED` (full suite green
afterwards) · `WONT_FIX` · `BLOCKED`.

---

## P3-01 — A single-use `AI_ROOT` grant was never consumed

| | |
|---|---|
| **Severity** | P1 (security-relevant authorisation defect) |
| **Component** | `core/permission/AuthorizationManager.kt` |
| **Status** | **VERIFIED** |

**Description.** `AI_ROOT` is the second gate an AI-initiated root command must clear. It
was evaluated with `evaluateSingle`, which by contract consumes nothing, and only the
`REMOTE_ROOT` grant was consumed afterwards. A grant issued as `GrantDuration.Once` on
`AI_ROOT` was therefore never spent.

**Reproduction.**
1. `grant(device, REMOTE_ROOT, UntilRevoked)`
2. `grant(device, AI_ROOT, Once)`
3. `authorize(device, REMOTE_ROOT, Initiator.AI)` — allowed, as intended
4. `authorize(device, REMOTE_ROOT, Initiator.AI)` again

**Expected.** Step 4 denied with `AI_ROOT_REQUIRED`; the owner authorised the model once.

**Actual.** Step 4 allowed. "Allow the model to do this once" silently meant "for as long as
`REMOTE_ROOT` lives".

**Root cause.** The AI gate was read but not written back. Consumption was applied only to
the decision returned to the caller.

**Fix.** `evaluate` now retains the `AI_ROOT` grant and consumes it alongside the root grant
— and only when the command is ultimately authorised, so a refusal at the second gate still
cannot burn the first.

**Files changed.** `AuthorizationManager.kt`

**Tests.** `AuthorizationManagerTest`:
`a single-use AI_ROOT grant is spent after one AI root command`,
`spending a single-use AI_ROOT leaves the human root grant usable`

---

## P3-02 — A root command refused for want of root was recorded as having executed

| | |
|---|---|
| **Severity** | P2 (audit integrity) |
| **Component** | `core/root/RootCommandHandler.kt` |
| **Status** | **VERIFIED** |

**Description.** When the capability probe reported no root provider, the refusal was
written to the audit trail as `ROOT_EXECUTED` with `success = false`.

**Reproduction.** Grant `REMOTE_ROOT`, make root unavailable, submit an elevated command.

**Expected.** No `ROOT_EXECUTED` record; a refusal record instead.

**Actual.** A `ROOT_EXECUTED` record for a command that never ran.

**Root cause.** The event type was chosen by the code path's location rather than by what
happened.

**Fix.** Recorded as `AUTHORIZATION_DENIED` with the reason in `detail`. The owner reads this
trail to answer "what ran as root on my phone"; a refusal must not appear in that answer.

**Files changed.** `RootCommandHandler.kt`

**Tests.** `RootCommandHandlerTest`:
`a command refused for want of root is not recorded as having executed`

---

## P3-03 — An empty command was refused without an audit record

| | |
|---|---|
| **Severity** | P2 (audit completeness) |
| **Component** | `core/root/RootCommandHandler.kt` |
| **Status** | **VERIFIED** |

**Description.** Every refusal path recorded an audit event except the blank-command check,
which returned silently. It also ran *after* the replay guard, so a malformed request
consumed a request id that a corrected retry would then be refused for reusing.

**Expected.** A refusal record, and no id burned by a request that never reached
authorisation.

**Actual.** No record; id consumed.

**Fix.** The shape check moved ahead of the replay guard and now records the refusal.

**Files changed.** `RootCommandHandler.kt`

**Tests.** `RootCommandHandlerTest`: `an empty command is refused and recorded`

---

## P3-04 — Clearing the audit log was recorded as a permission revocation

| | |
|---|---|
| **Severity** | P2 (audit integrity) |
| **Component** | `core/audit/AuditLogger.kt` |
| **Status** | **VERIFIED** |

**Description.** `clear()` recorded its own occurrence as `PERMISSION_REVOKED`, so an owner
filtering the trail for revocations — "when did I take this device's access away?" — saw an
event that never happened.

**Fix.** Added `AuditEventType.AUDIT_CLEARED` and used it. Also collapsed the two `clock()`
reads in `record` into one, so an entry's id and timestamp cannot disagree.

**Files changed.** `AuditLogger.kt`

**Tests.** `AuditLoggerTest`:
`clearing the log is recorded as its own event, not as a revocation`

---

## P3-05 — The replay guard's remembered-id set was unbounded

| | |
|---|---|
| **Severity** | P2 (resource growth, reachable by an authenticated peer) |
| **Component** | `core/permission/RequestGuard.kt` |
| **Status** | **VERIFIED** |

**Description.** Ids were evicted only by age. Within the ten-minute window the set grew
with every distinct id, so a peer could enlarge it without limit simply by sending requests.

**Fix.** A ceiling that **fails closed**: at the limit the guard refuses new requests rather
than evicting an id still inside its window. Evicting early would silently stop protecting
against the replay of exactly that request, which is the one thing the class exists to
prevent. Refusing is visible and self-healing as ids age out.

**Files changed.** `RequestGuard.kt`

**Tests.** `RequestGuardTest`:
`the guard refuses rather than forgetting an id that is still within its window`,
`capacity is recovered as ids age out`

---

## Findings recorded but not "fixed"

### F-01 — The authorisation core is unreachable from the application

**Severity:** architectural, not a code defect. Nothing outside `core/permission`,
`core/root` and `core/audit` referenced any of it; the only `PairedDeviceRegistry` and
`ShellExecutor` collaborators lived in test sources. The components were correct and tested;
the app did not call them.

**Status: FIXED.** The command path now runs through the core. `DeviceIdentity` derives a
device id from the pairing secret, `PrivilegedCommandGateway` translates a wire request into
a `ShellCommandRequest`, and `CommandDispatcher` routes the new `shell` and `shell_root`
commands through `RootCommandHandler`. Grants are persisted, and the app carries an owner UI
to issue and revoke them.

Verified by mutation rather than by assertion alone: removing the authorisation check from
`RootCommandHandler` turns 18 tests red, 9 of them in the new
`CommandDispatcherPrivilegedTest`, which exercises the path from a `CommandRequest` and
asserts on whether a command *ran*.

### F-02 — There is no AI-specific gate for non-root AI commands

`AI_ROOT` guards `REMOTE_ROOT` only. An AI-initiated *unprivileged* shell command needs
`REMOTE_SHELL` and nothing more. That is consistent with the documented model
(`REMOTE_AI ≠ REMOTE_ROOT`), but it is a design decision the owner should confirm rather
than an oversight to patch. Inventing an `AI_SHELL` permission would be a new feature, which
Phase 3 excludes. **Status: OPEN, design question.**

### F-03 — Grant ids can collide within a millisecond

`grant()` derived the id from `clock()`, the permission and a device-id prefix. Two grants of
the same permission to the same device inside one millisecond produced the same id, and the
store is keyed by id, so the superseded (revoked) record was overwritten rather than
retained. Behaviour stayed correct ("newest wins", which is intended); what was lost was a
line of audit history.

**Status: FIXED**, and fixed before persistence rather than after — on disk the collisions
would have been permanent. Ids now carry a per-process sequence number alongside the clock.

### F-04 — `onAudit` is invoked while the authorisation lock is held

`authorize` is `@Synchronized` and calls the audit callback inside that block. No cycle
exists today (the logger never calls back into the manager), but a persistent logger doing
I/O would serialise every authorisation behind a disk write. **Status: OPEN, P3, latent.**

### F-05 — Expiry depends on wall-clock time

`GrantDuration.Until` is evaluated against `System.currentTimeMillis()`, which can move
backwards. Revocation and single-use consumption are unaffected. Anyone able to change the
device clock is the owner, so this is a documented property rather than an attack path.
**Status: WONT_FIX**, recorded deliberately.

---

## Fuzzing result

~28,000 hostile dispatches against the live `CommandDispatcher` — wrong types, extreme
numerics, oversized strings, shell/format/path-traversal payloads as parameter *values*,
unknown commands, unknown and empty parameter names, and a seeded random sweep — produced
**no exception and no unstructured failure**. Every refusal carried an error code and a
non-empty message.

This found no bug. Recorded because a clean fuzz result is a result, and because the suite
now guards the property.
