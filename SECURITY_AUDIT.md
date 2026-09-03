# Security Audit

Date: 2026-09-03
Scope: the shipped transport and command path, including the authorised shell route.
Companion to [SECURITY.md](SECURITY.md), which states the design; this document records what
an adversarial read of the implementation found.

**Framing.** The authorisation core is now reachable from the running application: `shell`
and `shell_root` commands route through it, and are refused unless the owner has granted the
matching permission. Statements below about root authorisation therefore describe what the
app enforces, not what a library could enforce. Ordinary automation commands — tap,
screenshot, UI tree — remain governed by transport authentication alone, deliberately: a
peer holding the pairing secret already has full screen read and control, so a second gate
in front of `tap` would break every user and buy nothing.

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
| Shell / root commands | Reachable only with an explicit, unexpired, unrevoked owner grant; none exists by default |

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
| ~~F-01~~ | — | **Closed.** The core is on the command path; see `PHASE_3_BUGS.md` |
| F-02 | Design question | `AI_ROOT` gates root only. An AI-initiated *unprivileged* shell needs `REMOTE_SHELL` alone; there is no AI-specific gate for it |
| F-06 | Stated limitation | The `initiator` field is supplied by the peer, so `AI_ROOT` is a policy control between an honest client and its owner, not a boundary against a hostile one. DroidPilot's MCP server declares every command as AI-initiated, which is truthful and makes the gate meaningful in the shipped deployment |
| ~~F-03~~ | — | **Closed.** Ids carry a sequence number; fixed before grants were persisted |
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

**There is now a shell on the live path**, and this section is written on the assumption
that a reader has come here to find out how dangerous that is.

For the automation commands, nothing changed: `CommandDispatcher` routes every parameter to
accessibility selectors and gesture APIs, never to a command interpreter — confirmed by
fuzzing with shell, format-string and path-traversal payloads as parameter values (~28,000
dispatches, no exception, every refusal structured). Those parameters cannot reach a shell
because there is no code path from them to one.

For `shell` and `shell_root`, the command string is passed to a shell *by design* — it is
the payload the owner explicitly authorised, and interpreting it is the entire feature.
There is no injection boundary to defend inside it, and no blocklist pretending otherwise.
The boundary sits in front: owner authorisation, an identity derived from the pairing
secret, a live grant for the specific permission, and replay rejection. A caller who reaches
the shell has been authorised to reach the shell.

Where DroidPilot builds a command from its *own* parameters, `ShellExecutor.executeArgv`
takes an argument vector with no shell to interpret it. That split — `execute` for an
owner-authored command, `executeArgv` for anything assembled from parts — is the real
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
  legitimate client. Device identity is *derived* from the secret rather than asserted, which
  makes grants revocable by rotating it — but it does not distinguish two holders of the same
  secret, because nothing can.
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

1. **Persist the audit trail.** Grants now survive a restart; the trail does not, so the
   record of what was run with elevated access is lost when the process dies — which is
   precisely when someone would want to read it.
2. **Move the audit callback outside the authorisation lock** (F-04) before the logger does
   any I/O. Persisting the trail is exactly the change that would make this bite.
3. **Get the grant UI under test.** The authorisation logic behind it is covered thoroughly;
   the screen that issues and revokes grants is not, and it is now the least-verified part
   of a security-critical flow.
4. **Decide F-02** — whether AI-initiated unprivileged shell needs its own gate.
5. **Consider forward secrecy** for the transport if the threat model ever includes an
   attacker who records traffic now and compromises the secret later.
