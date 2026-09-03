# Root Authorization

How DroidPilot decides whether to run a command as root on this device.

Root is the most consequential thing DroidPilot can do. This document states the rules
exactly, because a security model that is only in the code is one nobody can audit.

## The rule

A root command runs only when **all** of these hold, checked in this order, on every
command:

| # | Check | Enforced by |
|---|---|---|
| 1 | The peer authenticated with the pairing secret during the WebSocket upgrade | `AuthGate` |
| 2 | The request is fresh and its id has not been used | `RequestGuard` |
| 3 | The device is paired | `AuthorizationManager` |
| 4 | A grant exists for that device and `REMOTE_ROOT` | `AuthorizationManager` |
| 5 | The grant is not revoked | `AuthorizationManager` |
| 6 | The grant has not expired | `AuthorizationManager` |
| 7 | A single-use grant has not already been spent | `AuthorizationManager` |
| 8 | If the AI initiated it, `AI_ROOT` is *also* granted | `AuthorizationManager` |
| 9 | A root provider is present and accepted the request | `RootManager` |

Nothing is cached. The decision is recomputed from stored grants each time, which is what
makes revocation take effect on the next command rather than whenever a token lapses.

### Pairing is not authorization

Pairing establishes *who* a device is. It grants nothing. A freshly paired device holds no
permissions at all until the owner issues them, and **no trust-level preset includes
`REMOTE_ROOT` or `AI_ROOT`** — root is always its own explicit decision, with its own
prompt.

### Ordering is deliberate

- **Replay before authorization**, so a resent request cannot spend a single-use grant just
  by arriving twice.
- **Authorization before capability**, so an unauthorised peer cannot learn whether the
  device is rooted by comparing the two refusals. Both cases return the same message.

## Grant durations

| Duration | Behaviour |
|---|---|
| `Once` | Valid for exactly one successful authorization, then spent |
| `Until(t)` | Valid until wall-clock `t`; refused at `t` exactly |
| `UntilRevoked` | Valid until the owner revokes it |

A single-use grant is consumed when the authorization succeeds, not when the command
finishes. That is the safe direction: a command that ran and then failed has still run.

Re-granting the same permission supersedes the previous grant, so a newer owner decision
cannot be outlived by an older, longer one.

## AI_ROOT

A second gate that applies only when the AI subsystem — rather than a person at a paired
device — initiates a root command. It exists so this is expressible:

```
REMOTE_ROOT = allowed      (my laptop may run root commands)
AI_ROOT     = denied       (but the model may not)
```

`AI_ROOT` never substitutes for `REMOTE_ROOT`; an AI-initiated root command needs both. A
refusal on the `AI_ROOT` gate does **not** consume a single-use `REMOTE_ROOT` grant, so the
model cannot spend an authorization it was never permitted to use.

## What is deliberately absent

**There is no blocklist of dangerous commands.**

Once the owner has explicitly authorised root for a specific device, root commands from
that device are the feature working as intended. A blocklist would refuse legitimate
administration — remounting `/system`, restarting a service, editing a config — while
stopping nobody who can express the same operation another way. Its worst effect would be
inviting the belief that the list is the security boundary.

The boundary is: **owner authorization + device identity + a live grant.** Command preview
and confirmation belong in the UI, and are about preventing mistakes, not resisting an
attacker.

## Auditing

Every root execution and every refusal is recorded: device, permission, initiator, command,
exit code, duration, and the **sizes** of stdout and stderr.

Output content is never stored. A root command's stdout routinely contains exactly what must
not be written to a log the owner may export or attach to a bug report. Sizes preserve the
forensic signal without creating a second copy of the secret.

Command text *is* stored — a log that cannot say what ran is not an audit log — with
arguments to conventionally secret-bearing flags (`--token`, `--password`, …) redacted.
Redaction affects the log only; the command executes verbatim.

## Revocation

`revoke(device, permission)` marks matching grants revoked. The next command is refused.
Revoked grants are retained rather than deleted, so the audit trail remains complete.

`revokeAll(device)` is the "remove this device" action.

## Current limitations

- **Grants are in-memory.** They do not survive a process restart yet; a persistent
  Keystore-backed store is the next task.
- **The elevated execution path has not been run against a real root provider.** The
  authorization logic, refusal paths and capability probe are covered by 22 tests against a
  fake executor, but no emulator image has a root provider, so `ProcessShellExecutor`'s
  `su` path is verified by construction rather than by execution. Verify this on a real
  rooted device before relying on it.
- **There is no UI yet** for granting, viewing or revoking root. The engine is complete;
  the screens are a later phase.
