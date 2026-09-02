# DroidPilot

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-green.svg)](https://developer.android.com)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-blue.svg)](https://modelcontextprotocol.io)

**Android device automation for AI agents, over Accessibility Service + MCP.**

> Control an Android device from Claude, or any MCP-compatible AI — no ADB, no USB, no screen
> mirroring. Just Wi-Fi.

DroidPilot reads the device's UI tree directly through Android's Accessibility APIs and acts
on it through OS gesture dispatch. That is structurally more reliable than OCR over
screenshots, and dramatically cheaper in tokens: the agent gets labelled, searchable
elements instead of an image it has to interpret.

## Key features

- **No ADB required** — connects over Wi-Fi via WebSocket
- **Native UI tree access** — no screenshot OCR or computer vision
- **Authenticated and encrypted** — every connection needs a pairing secret; every frame is AES-256-GCM
- **Reliable actions** — taps, swipes and text input through OS APIs
- **MCP native** — works with Claude Desktop, Claude Code, and any MCP client
- **Low token cost** — structured UI data instead of image analysis
- **Honest capability reporting** — the device tells the agent what it actually can and cannot do

## How it works

```
┌──────────────┐   MCP / stdio   ┌──────────────┐   WebSocket    ┌─────────────────────┐
│  AI agent    │ ◄─────────────► │  MCP server  │ ◄────────────► │  Android device     │
│  (Claude, …) │                 │  (Node.js)   │  authenticated │  Accessibility svc  │
│              │                 │              │   + encrypted  │  + control server   │
└──────────────┘                 └──────────────┘                └─────────────────────┘
```

## Why DroidPilot

| Approach | Reliability | Speed | Token cost | Setup |
|---|---|---|---|---|
| ADB-based | Low — connection drops, limited UI access | Medium | High (screenshot analysis) | USB / Wi-Fi ADB |
| Screen mirroring + OCR | Low — OCR errors, high latency | Slow | Very high | Complex |
| **DroidPilot** | **High — native OS integration** | **Fast** | **Low (structured data)** | **Install APK** |

---

## Quick start

### 1. Build and install the app

**Requirements:** Android 11+ (API 30), and a PC on the same network.

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio.

Then on the device:

1. Open **DroidPilot**.
2. Tap **Open Accessibility settings** and enable **DroidPilot**.
3. Return to the app and tap **Start server**.
4. Tap **Copy pairing URI**. You now have a string like
   `droidpilot://192.168.1.42:8765#<secret>`.

> The pairing secret is what protects your device. Anyone holding it can read your screen
> and control the device while the server is running. Treat it like your lock-screen PIN.
> You can regenerate it at any time from the app.

### 2. Build the MCP server

```bash
cd mcp-server
npm install
npm run build
```

### 3. Configure your MCP client

**Claude Desktop** — add to `claude_desktop_config.json`; **Claude Code** — add to your MCP
settings. Same shape for both:

```json
{
  "mcpServers": {
    "droidpilot": {
      "command": "node",
      "args": ["/absolute/path/to/droidpilot/mcp-server/dist/index.js"]
    }
  }
}
```

### 4. Connect

Paste the pairing URI:

```
Connect to my Android device: droidpilot://192.168.1.42:8765#<secret>
```

Then work in plain language:

```
Open Chrome and search for the weather
Find the search bar and type "hello world"
Scroll down and tell me what you see
Press the back button
```

---

## Available MCP tools

| Tool | Description |
|---|---|
| `connect` | Connect using a pairing URI, or host + port + secret |
| `disconnect` | Disconnect from the device |
| `get_device_info` | Model, Android version, screen size, and granted capabilities |
| `get_ui_tree` | Full UI hierarchy as structured data — **prefer this over `screenshot`** |
| `find_element` | Search by text, resource id, class name or content description |
| `click_element` | Find and click — **prefer this over `tap`**; survives layout changes |
| `long_click_element` | Find and long-press, for context menus |
| `wait_for_element` | Wait for an element to appear, with a timeout |
| `screenshot` | Capture the screen as JPEG (expensive; use only when you must see rendering) |
| `tap` / `long_press` | Act on absolute coordinates, for canvases and maps |
| `swipe` / `scroll` / `pinch` | Gestures |
| `type_text` / `set_text` | Append to, or replace, the focused input field |
| `press_key` | back, home, recents, notifications, quick_settings, power_dialog, split_screen, lock_screen, take_screenshot |
| `open_app` | Launch an app by package name |
| `get_focused` | Describe the currently focused input element |

### A note on screenshots

Prefer `get_ui_tree` and `find_element`. A UI dump is structured, exact, searchable, and
roughly two orders of magnitude cheaper in tokens than an image. Reach for `screenshot` only
when you genuinely need to *see* rendering — images, charts, canvas content with no
accessibility representation. Android also rate-limits captures to about one per second.

---

## Security

Every connection is authenticated with a 256-bit pairing secret and encrypted with
AES-256-GCM. The secret is checked **during the HTTP upgrade**, so an unauthenticated peer
never reaches a state where it could send a command. It is stored wrapped by a
hardware-backed Android Keystore key and excluded from backups.

Password fields are never transmitted, and typed text is never echoed back or logged.

**If you are running DroidPilot 1.0, upgrade.** That release shipped with an `authToken`
field that was never assigned anywhere, so every 1.0 server accepted any client on the
network with no credential at all.

Full details, including the cryptographic construction and an explicit threat model, are in
[SECURITY.md](SECURITY.md).

### Reducing exposure further

Enable **Loopback only** in the app and tunnel over ADB:

```bash
adb forward tcp:8765 tcp:8765
```

The server is then unreachable from the network entirely; connect to `127.0.0.1`.

---

## Use cases

- **AI-powered mobile testing** — agents running QA flows on real devices
- **Mobile RPA** — automating repetitive tasks across any Android app
- **Accessibility automation** — assistive workflows
- **App monitoring** — periodic UI state checks
- **Cross-app workflows** — orchestrating actions across multiple apps

## Development

```bash
# Android
cd android
./gradlew testDebugUnitTest    # unit tests
./gradlew lintDebug            # lint
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (see CONTRIBUTING.md for signing)

# MCP server
cd mcp-server
npm run typecheck
npm test
npm run build
```

144 tests, none requiring a physical device. See [CONTRIBUTING.md](CONTRIBUTING.md) for the
full workflow and [ARCHITECTURE.md](ARCHITECTURE.md) for how the pieces fit together.

## Documentation

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Layering, design decisions, testing strategy, limitations |
| [SECURITY.md](SECURITY.md) | Authentication, encryption, permissions, threat model |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Building, testing, release signing, conventions |
| [CHANGELOG.md](CHANGELOG.md) | Release history |

## Tech stack

- **Android:** Kotlin 2.0, Coroutines, kotlinx.serialization, Material 3, Java-WebSocket
- **MCP server:** TypeScript, Node 20+, `@modelcontextprotocol/sdk`
- **Transport:** WebSocket, authenticated and encrypted with AES-256-GCM

## Contributing

Contributions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
