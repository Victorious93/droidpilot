# Security Audit

Date: 2026-09-03
Scope: the shipped transport and command path, plus the authorisation core as a library.
Companion to [SECURITY.md](SECURITY.md), which states the design; this document records what
an adversarial read of the implementation found.

**A framing point that governs everything below.** The authorisation core is not reachable
from the running application (see `PHASE_3_AUDIT.md` §3). Its properties are real and
tested, but they currently protect a library, not a user. Any sentence here about root
authorisation describes code that no production path invokes.

---

## 1. Live attack surface

What an attacker on the same network can actually reach today:

| Surface | Exposure |
|---|---|
| WebSocket control server | Only while the user has explicitly started it |
| Bind address | Configurable; "Loopback only" restricts it to `127.0.0.1` |
| Authentication | 256-bit pairing secret, verified during the HTTP upgrade |
| Post-handshake frames | AES-256-GCM, replay-rejecting, direction-separated |
| Outbound requests | None — the app makes no network requests of its own |

There is no unauthenticated endpoint. A peer that fails authentication is rejected inside
`onWebsocketHandshakeReceivedAsServer`, before a WebSocket exists, so it never reaches a
state in which a command could be buffered or delivered.

---

## 2. Findings

### Confirmed and fixed in Phase 3

| Id | Severity | Finding |
|---|---|---|
| P3-01 | P1 | A single-use `AI_ROOT` grant was never consumed, so "allow the model once" meant "until `REMOTE_ROOT` ends" |
| P3-02 | P2 | A root command refused for want of a provider was recorded in the audit trail as executed |
| P3-03 | P2 | An empty command was refused with no audit record, and burned a replay-guard id |
| P3-04 | P2 | Clearing the audit log recorded itself as a permission revocation |
| P3-05 | P2 | The replay guard's remembered-id set was unbounded and grew at an authenticated peer's discretion |

Detail, reproductions and tests: `PHASE_3_BUGS.md`.

### Confirmed present and correct

Each was probed for a bypass and none was found; each is covered by tests.

- **Pairing is not authorisation.** Every permission is denied to a paired device with no
  grant, checked exhaustively across the whole `RemotePermission` enum.
- **No preset confers root.** Trust levels expand into individual grants at application time
  and are never consulted at execution time. The preset named `ROOT` deliberately excludes
  `REMOTE_ROOT` and `AI_ROOT`.
- **Permissions do not imply one another.** `REMOTE_SHELL ≠ REMOTE_ROOT ≠ AI_ROOT`;
  `REMOTE_VIEW ≠ REMOTE_SETTINGS`; a grant for one authorises only that one.
- **Revocation is immediate.** A grant is a record the device re-reads per command, not a
  token the peer holds, so nothing survives revocation by having been issued earlier.
- **Expiry and single-use are enforced at the point of use**, together with revocation, in
  one predicate rather than split across call sites.
- **Replay is rejected before authorisation**, so a resent request cannot spend a single-use
  grant by arriving twice.
- **Capability is probed after authorisation**, so an unauthorised peer cannot distinguish
  "refused" from "not rooted" and use the difference to fingerprint the device.
- **Root detection is provider-agnostic** — it asks whether uid 0 was actually obtained
  rather than looking for a known root manager, and never fabricates availability.
- **Timeouts are clamped** to `ShellLimits.MAX_TIMEOUT_MILLIS`, so a remote peer cannot pin a
  process open indefinitely; output is capped per stream.
- **Command output is never written to the audit log** — only byte counts.

### Open, not fixed

| Id | Severity | Finding |
|---|---|---|
| F-01 | Architectural | The authorisation core guards nothing at runtime; it is unreachable from the app |
| F-02 | Design question | `AI_ROOT` gates root only. An AI-initiated *unprivileged* shell needs `REMOTE_SHELL` alone; there is no AI-specific gate for it |
| F-03 | P3 | Grant ids can collide inside one millisecond, overwriting the superseded record. Must be fixed before grants are persisted |
| F-04 | P3, latent | The audit callback runs while the authorisation lock is held; a persistent logger doing I/O would serialise authorisation behind disk writes |
| F-05 | Accepted | Grant expiry uses wall-clock time, which can move backwards. Revocation and single-use are unaffected; only the device owner can change the clock |

---

## 3. Credential handling

Checked by grep across `main` and `mcp-server/src`, and by reading every logging call on the
privileged paths.

| Question | Answer |
|---|---|
| Hardcoded credentials, keys, tokens, device ids? | None. The only grep hits are a redaction flag-list and a `SharedPreferences` key name |
| Is the pairing secret stored in the clear? | No — wrapped by a non-exportable Android Keystore key, excluded from backups, verified by an instrumented test asserting the plaintext does not appear in the stored form |
| Is the secret logged? | No |
| Is command output logged? | No — sizes only |
| Is command text logged? | Yes, deliberately, with conventional secret-bearing flags redacted. Stated as a courtesy rather than a guarantee: a secret typed in another shape is recorded verbatim |
| Is typed text echoed back? | No |
| Is TLS verification disabled anywhere? | No TLS is used; the channel is AES-256-GCM over a plain WebSocket on the local network. See §5 |

---

## 4. Injection

There is no shell on the live path. `CommandDispatcher` routes every parameter to
accessibility selectors and gesture APIs, never to a command interpreter — confirmed by
fuzzing with shell, format-string and path-traversal payloads as parameter values (~28,000
dispatches, no exception, every refusal structured).

On the library path, `ShellExecutor` deliberately exposes two entry points: `execute` takes
the command as a single string, because that string *is* the payload the owner authorised;
and `executeArgv` takes an argument vector with no shell, which is the required choice
whenever any part of the command comes from the app's own parameters. That split is the
injection boundary, and it is documented at the interface.

---

## 5. Transport, stated plainly

The channel is **not TLS**. It is AES-256-GCM with keys derived by HKDF-SHA256 from the
pairing secret and a per-session salt, with direction separation and strictly increasing
record counters. That provides confidentiality, integrity and replay resistance against a
network attacker, and it authenticates the peer as *someone holding the pairing secret*.

What it does not provide:

- **No forward secrecy.** Traffic recorded today can be decrypted by anyone who later learns
  the secret. Regenerating the secret bounds the exposure window.
- **No identity beyond the secret.** Anyone holding it is indistinguishable from the
  legitimate client. There is no device identity layer yet.
- **No certificate infrastructure**, and therefore no certificate validation to weaken. No
  code disables verification anywhere, because there is none to disable.

A Phase 3 fix in the shipped transport is worth noting here because it was security-adjacent:
sealing a record and writing it to the socket were separate steps, so two concurrent
responses could be numbered *n* and *n+1* and reach the socket in the opposite order. The
peer correctly rejected the late one as a replay — the guarantee held — but a client silently
lost an answer. Sealing and writing are now serialised per connection.

---

## 6. Security principles, checked against the code

| Principle | Holds? | Where |
|---|---|---|
| `PAIRING ≠ AUTHORIZATION` | Yes | `AuthorizationManager.evaluate` — pairing is step 1 of 6 |
| `REMOTE_SHELL ≠ REMOTE_ROOT` | Yes | Distinct enum entries, no implication anywhere |
| `REMOTE_ROOT ≠ AI_ROOT` | Yes | AI-initiated root requires both; fixed in P3-01 so the AI gate is also spent |
| `DISCOVERY ≠ TRUST` | N/A | No discovery mechanism exists |
| `AI_OUTPUT ≠ EXECUTION` | Partly | The `Initiator` distinction exists and is enforced for root. No AI subsystem exists to produce output, so the wider property is untested |
| `ROOT_GRANT ≠ PERMANENT_PRIVILEGE` | Yes | Once / Until / UntilRevoked, all evaluated per command |
| `PERMISSION_REVOCATION = IMMEDIATE FUTURE DENIAL` | Yes | Recomputed per command from stored grants |

---

## 7. Recommendations, in priority order

1. **Wire the authorisation core into a real command path, or say plainly that it guards
   nothing.** This is the single largest gap between what the documentation implies and what
   the app enforces. The README's status table now says so; the code should eventually make
   it unnecessary.
2. **Persist grants and the audit trail** — both are in-memory and vanish with the process,
   which means revocation does not survive a restart and the trail cannot be reviewed after
   one. Fix F-03 first, since persistence makes id collisions permanent.
3. **Move the audit callback outside the authorisation lock** (F-04) before the logger does
   any I/O.
4. **Decide F-02** — whether AI-initiated unprivileged shell needs its own gate.
5. **Consider forward secrecy** for the transport if the threat model ever includes an
   attacker who records traffic now and compromises the secret later.
