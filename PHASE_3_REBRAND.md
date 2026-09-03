# Phase 3 — Rebrand to DroidPilot Forge

Date: 2026-09-03

---

## Identity

| | Before | After |
|---|---|---|
| Public name | DroidPilot | **DroidPilot Forge** |
| Tagline | *Android device automation for AI agents, over Accessibility Service + MCP* | **An enhanced, agentic evolution of DroidPilot.** |
| Launcher / app label | `DroidPilot` | `DroidPilot Forge` |
| Notification title | `DroidPilot` | `DroidPilot Forge` |
| Application id | `com.mobilemcp.pro` | **unchanged — see below** |
| Kotlin namespace | `com.mobilemcp.pro` | **unchanged** |
| Pairing URI scheme | `droidpilot://` | **unchanged** |
| MCP server key | `droidpilot` | **unchanged** |

---

## What was deliberately *not* renamed, and why

The brief asked to distinguish public branding from internal identifiers. Four things were
left alone on technical grounds rather than oversight.

**Application id `com.mobilemcp.pro`.** Changing it makes the new build a different app to
Android. The consequences are not cosmetic: existing installations would not upgrade in
place, the user's Accessibility grant would have to be re-issued, the Keystore-wrapped
pairing secret would be orphaned in the old app's data directory, and every paired MCP
client would need re-pairing. The id was already marked as deliberately fixed in
`build.gradle.kts`; that decision stands. A rename would be a migration project, not a
rebrand.

**Kotlin package and class names** (`com.mobilemcp.pro.*`, `DroidPilotAccessibilityService`,
`DroidPilotApplication`). Internal identifiers with no user-visible surface. Renaming them
would churn every file, invalidate the accessibility service's stored component name — which
is exactly the string the system persists in `enabled_accessibility_services` — and buy
nothing.

**`droidpilot://` URI scheme.** Parsed by `PairingSecret.parseUri` and by the MCP server.
Changing it breaks every pairing URI a user has already copied, with no benefit.

**MCP server key `droidpilot`** in client configuration. Changing it silently breaks every
existing `claude_desktop_config.json`. The README now says explicitly that the key is kept
for that reason.

**Original attribution and licence.** The MIT licence and the notice
`Copyright (c) 2026 youichi-uda` are preserved verbatim in `LICENSE`, as the licence
requires. The README's Attribution section names the origin project, restates the copyright,
and describes this work as a modified and expanded version rather than an original one.

---

## Logo

| | |
|---|---|
| Source | uploaded JPEG, 1408 × 1408, RGB |
| Stored as | `docs/images/droidpilot-forge-logo.png` |
| Format | PNG, 512 × 512, RGB, 364 KB |
| README reference | `<img src="docs/images/droidpilot-forge-logo.png" width="220">` |

Resized rather than stored at source resolution: the full-size PNG was 2.3 MB for an asset
the README renders at 220 px, and 512 px still gives a 2× margin for high-density displays.
A single asset is kept — no duplicate sizes — to avoid two files drifting apart.

`docs/images/` is a new directory; the repository had no documentation-asset location.

---

## README

Rewritten. Preserved from the previous README because it was accurate and useful: the MCP
tool table, the quick-start flow, the screenshots-versus-UI-tree guidance, the
approach-comparison table, the loopback/ADB-tunnel note, the tech stack, and the
documentation index.

Added:

- Logo, name, tagline, and badges that point only at real things (MIT licence, Android API
  level, MCP, Kotlin version). No build, coverage, release or download badges were invented.
- **An implementation-status section in three parts** — implemented and tested, implemented
  as a library but unreachable from the app, and not implemented. This is the substantive
  change. The Phase 2 feature list describes far more than the repository contains, and a
  README that repeated it would have been the single most misleading file in the project.
- An authorization-core section, explicitly marked library-only.
- A privileged/root section stating that the project cannot root a device and only uses
  access a device already provides.
- Operating modes, with Pilot Mode marked implemented and Developer Mode marked planned,
  including why an on-device build loop is not feasible.
- Build prerequisites with the versions actually in `libs.versions.toml`, real APK output
  paths, and the environment variables release signing actually reads.
- A project structure generated from the real tree, annotating which packages are
  library-only.
- Limitations and a roadmap split into completed / in development / planned.

Validated after writing: every local link and image path resolves, every table-of-contents
anchor matches a real heading, code fences balance, and the only remaining bare "DroidPilot"
mentions are the three intentional references to the origin project.

---

## Android user-facing branding

`android/app/src/main/res/values/strings.xml` — six strings:

| String | Change |
|---|---|
| `app_name` | `DroidPilot` → `DroidPilot Forge` (launcher, title bar, Accessibility settings entry, service label) |
| `accessibility_service_description` | two occurrences renamed |
| `accessibility_hint` | renamed |
| `notification_permission_rationale` | renamed |
| `notification_channel_description` | renamed |
| `notification_title` | `DroidPilot` → `DroidPilot Forge` |

`app_tagline` was left as *"Accessibility-based device automation for MCP clients"*. In the
app the title directly above it already reads "DroidPilot Forge", so the useful thing for
that line to do is say what the app does, not repeat the name. The marketing tagline is used
in the README.

**One coupling checked, then executed.** Three instrumented tests search the live UI for the
text `"DroidPilot"`. `ElementSelector` defaults to `exact = false`, so substring matching
means they still match `"DroidPilot Forge"` — reasoning that CI's emulator job has since
confirmed, with all three running and passing on the renamed build. Had the default been
exact matching, the rename would have broken them.

---

## Files

**Added**

```
docs/images/droidpilot-forge-logo.png
PHASE_3_AUDIT.md
PHASE_3_BUGS.md
PHASE_3_TEST_RESULTS.md
PHASE_3_REBRAND.md
SECURITY_AUDIT.md
android/app/src/test/kotlin/com/mobilemcp/pro/core/audit/AuditLoggerTest.kt
android/app/src/test/kotlin/com/mobilemcp/pro/server/CommandDispatcherFuzzTest.kt
```

**Modified — branding and documentation**

```
README.md
android/app/src/main/res/values/strings.xml
```

**Modified — Phase 3 bug fixes (not part of the rebrand)**

```
android/app/src/main/kotlin/com/mobilemcp/pro/core/audit/AuditLogger.kt
android/app/src/main/kotlin/com/mobilemcp/pro/core/permission/AuthorizationManager.kt
android/app/src/main/kotlin/com/mobilemcp/pro/core/permission/RequestGuard.kt
android/app/src/main/kotlin/com/mobilemcp/pro/core/root/RootCommandHandler.kt
android/app/src/test/kotlin/com/mobilemcp/pro/core/permission/AuthorizationManagerTest.kt
android/app/src/test/kotlin/com/mobilemcp/pro/core/permission/RequestGuardTest.kt
android/app/src/test/kotlin/com/mobilemcp/pro/core/root/RootCommandHandlerTest.kt
```

**Removed**

None.

---

## Build verification after the rebrand

```
./gradlew testDebugUnitTest lintDebug lintRelease \
          assembleDebug assembleRelease assembleDebugAndroidTest --offline
```

**BUILD SUCCESSFUL** in 2m 14s. 187 unit tests, 0 failures, 0 skipped. Lint clean on debug
and release. MCP server: typecheck, 38 tests, and `tsc` build all pass.

The renamed strings are referenced from the manifest (`android:label`) and the layout, so a
mistyped resource name would have failed the build rather than shipping silently.

---

## Known limitations of this rebrand

- **The launcher icon is unchanged.** The new logo is a documentation asset only. Generating
  adaptive-icon densities from it is a separate piece of work with its own review — a
  1408 px square artwork does not become a good adaptive icon by resizing, because Android
  masks it and crops to a safe zone, and the logo's text would be cut off.
- **The launcher icon is still the old one**, per the point above — the rebrand is complete
  in text and incomplete in iconography.
- **Repository metadata was not touched** — no GitHub settings, description, or topics were
  changed.
