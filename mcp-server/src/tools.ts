import { z } from "zod";

/**
 * MCP tool surface.
 *
 * Descriptions are written for the model that reads them, so each one says when to reach
 * for the tool and what it costs — not merely what it does. The steer toward `get_ui_tree`
 * over `screenshot` is the highest-leverage line in this file: a UI dump is structured,
 * searchable and roughly two orders of magnitude cheaper in tokens than an image, and a
 * model given both will otherwise reach for the picture.
 */
export const toolDefinitions = {
  shell: {
    description:
      "Run an unprivileged shell command on the device. Requires the device owner to have granted " +
      "REMOTE_SHELL in the DroidPilot app; without a live grant the device refuses and nothing runs. " +
      "A shell can read files and run binaries the accessibility automation cannot, which is why it is " +
      "authorised separately from pairing. Returns stdout, stderr and the exit code.",
    inputSchema: {
      command: z.string().min(1).describe("The command line to run, e.g. 'getprop ro.build.version.sdk'"),
      timeout: z
        .number()
        .int()
        .min(1000)
        .max(600000)
        .optional()
        .describe("How long the device should let the command run, in milliseconds (default 30000)"),
    },
  },

  shell_root: {
    description:
      "Run a shell command as root. Requires BOTH of two separate grants from the device owner: " +
      "REMOTE_ROOT, and AI_ROOT because this request comes from an AI. Either one missing means the " +
      "command is refused and nothing runs. It also requires the device to actually have a working root " +
      "provider — DroidPilot cannot root a device, only use root it already has. Every attempt, allowed " +
      "or refused, is recorded in the device's audit log. Prefer the unprivileged 'shell' tool unless " +
      "root is genuinely required.",
    inputSchema: {
      command: z.string().min(1).describe("The command line to run as root"),
      timeout: z
        .number()
        .int()
        .min(1000)
        .max(600000)
        .optional()
        .describe("How long the device should let the command run, in milliseconds (default 30000)"),
    },
  },

  connect: {
    description:
      "Connect to an Android device running DroidPilot. Must be called before any other tool. " +
      "Easiest usage: paste the pairing URI shown in the DroidPilot app (Copy pairing URI) as `pairingUri`. " +
      "Alternatively supply `host`, `port` and `secret` separately. The connection is authenticated " +
      "and encrypted with the pairing secret; a wrong secret is refused by the device.",
    inputSchema: {
      pairingUri: z
        .string()
        .optional()
        .describe("Pairing URI from the app, e.g. droidpilot://192.168.1.42:8765#<secret>"),
      host: z.string().optional().describe("Device IP address, if not using pairingUri"),
      port: z.number().int().min(1).max(65535).optional().describe("Port (default 8765)"),
      secret: z.string().optional().describe("Pairing secret from the app, if not using pairingUri"),
    },
  },

  disconnect: {
    description: "Disconnect from the currently connected device.",
    inputSchema: {},
  },

  get_device_info: {
    description:
      "Device manufacturer, model, Android version, screen size, and the list of capabilities " +
      "this device actually grants. Check `capabilities` before relying on screenshots or gestures — " +
      "a device may withhold either.",
    inputSchema: {},
  },

  get_ui_tree: {
    description:
      "Read the UI hierarchy of the current screen as structured data: every element with its text, " +
      "bounds, resource id and interaction flags. PREFER THIS OVER `screenshot` for understanding what " +
      "is on screen, finding elements, and checking assertions — it is far cheaper in tokens, exact, " +
      "and directly searchable. Reach for a screenshot only when you genuinely need to see rendering. " +
      "If `truncated` comes back true, the tree hit the node budget: lower `maxDepth` or use `find_element`.",
    inputSchema: {
      maxDepth: z.number().int().min(0).max(50).default(15).describe("How deep to walk (default 15)"),
      maxNodes: z
        .number()
        .int()
        .min(1)
        .max(20000)
        .default(3000)
        .describe("Node budget for one request (default 3000)"),
    },
  },

  find_element: {
    description:
      "Find elements matching text, resource id, class name or content description. Cheaper than " +
      "`get_ui_tree` when you already know roughly what you are looking for. `text` matches either the " +
      "visible label or the content description. Matching is a case-insensitive substring unless `exact` is set.",
    inputSchema: {
      text: z.string().optional().describe("Visible text or content description to match"),
      id: z.string().optional().describe("Resource id, e.g. 'search_bar'"),
      className: z.string().optional().describe("Class name, e.g. 'android.widget.Button'"),
      contentDescription: z.string().optional().describe("Content description only"),
      exact: z
        .boolean()
        .default(false)
        .describe("Require exact equality instead of substring. Use when 'OK' must not match 'NOT OK'."),
      maxResults: z.number().int().min(1).max(200).default(10).describe("Result cap (default 10)"),
    },
  },

  click_element: {
    description:
      "Find an element and click it. PREFER THIS OVER `tap` — it survives layout changes, different " +
      "screen sizes and scrolling, whereas coordinates do not. If the matched element is not itself " +
      "clickable, its nearest clickable ancestor is used, which is what makes clicking a label inside a " +
      "button work. At least one criterion is required.",
    inputSchema: {
      text: z.string().optional().describe("Visible text or content description"),
      id: z.string().optional().describe("Resource id"),
      className: z.string().optional().describe("Class name"),
      contentDescription: z.string().optional().describe("Content description only"),
      exact: z.boolean().default(false).describe("Require exact equality instead of substring"),
    },
  },

  long_click_element: {
    description:
      "Find an element and long-press it — the usual way to open a context menu or enter a " +
      "selection mode. Same matching rules as `click_element`.",
    inputSchema: {
      text: z.string().optional().describe("Visible text or content description"),
      id: z.string().optional().describe("Resource id"),
      className: z.string().optional().describe("Class name"),
      contentDescription: z.string().optional().describe("Content description only"),
      exact: z.boolean().default(false).describe("Require exact equality instead of substring"),
    },
  },

  wait_for_element: {
    description:
      "Wait until an element appears, or the timeout elapses. Use this after any action that " +
      "triggers navigation or loading, instead of taking a screenshot and hoping the screen has settled. " +
      "A timeout is a normal result — it returns `found: false` rather than failing.",
    inputSchema: {
      text: z.string().optional().describe("Visible text or content description"),
      id: z.string().optional().describe("Resource id"),
      className: z.string().optional().describe("Class name"),
      contentDescription: z.string().optional().describe("Content description only"),
      exact: z.boolean().default(false).describe("Require exact equality instead of substring"),
      timeout: z.number().int().min(100).max(300000).default(10000).describe("Milliseconds (default 10000)"),
    },
  },

  screenshot: {
    description:
      "Capture the screen as a JPEG. EXPENSIVE — an image costs orders of magnitude more tokens than " +
      "`get_ui_tree` and cannot be searched. Use it only when you must actually see rendering: verifying " +
      "images, layout or canvas content that has no accessibility representation. For finding and acting " +
      "on elements, use `get_ui_tree` or `find_element`. Android rate-limits captures to roughly one per second.",
    inputSchema: {
      quality: z.number().int().min(1).max(100).default(80).describe("JPEG quality (default 80)"),
      maxDimension: z
        .number()
        .int()
        .min(120)
        .max(4096)
        .default(1600)
        .describe("Longest edge in pixels; the image is downscaled to fit (default 1600)"),
    },
  },

  tap: {
    description:
      "Tap absolute screen coordinates. Prefer `click_element` where possible — coordinates break when " +
      "layout, density or scroll position changes. Use this for canvases, maps, games and anything else " +
      "with no accessibility node.",
    inputSchema: {
      x: z.number().describe("X in pixels"),
      y: z.number().describe("Y in pixels"),
      duration: z.number().int().min(1).max(60000).default(100).describe("Contact time in ms (default 100)"),
    },
  },

  long_press: {
    description: "Press and hold at absolute coordinates. Prefer `long_click_element` where possible.",
    inputSchema: {
      x: z.number().describe("X in pixels"),
      y: z.number().describe("Y in pixels"),
      duration: z.number().int().min(1).max(60000).default(1000).describe("Hold time in ms (default 1000)"),
    },
  },

  swipe: {
    description:
      "Drag from one point to another — for dismissing cards, pull-to-refresh, or dragging a handle. " +
      "For ordinary list scrolling use `scroll`, which computes the geometry for you.",
    inputSchema: {
      startX: z.number().describe("Start X"),
      startY: z.number().describe("Start Y"),
      endX: z.number().describe("End X"),
      endY: z.number().describe("End Y"),
      duration: z.number().int().min(1).max(60000).default(300).describe("Duration in ms (default 300)"),
    },
  },

  scroll: {
    description:
      "Scroll the screen. `direction` is the direction the content moves: 'down' reveals content " +
      "further down the page.",
    inputSchema: {
      direction: z.enum(["up", "down", "left", "right"]).describe("Direction the content moves"),
      amount: z.number().min(1).max(10000).default(500).describe("Distance in pixels (default 500)"),
    },
  },

  pinch: {
    description: "Two-finger pinch to zoom. `scale` above 1 zooms in, below 1 zooms out.",
    inputSchema: {
      x: z.number().optional().describe("Centre X (default: screen centre)"),
      y: z.number().optional().describe("Centre Y (default: screen centre)"),
      scale: z.number().min(0.1).max(10).default(1.5).describe("Zoom factor (default 1.5)"),
      duration: z.number().int().min(1).max(60000).default(400).describe("Duration in ms (default 400)"),
    },
  },

  type_text: {
    description:
      "Append text to the focused input field. Click the field first — this fails if nothing is focused. " +
      "The device never echoes the text back, and refuses to type into a password field's neighbours blindly.",
    inputSchema: {
      text: z.string().max(10000).describe("Text to append"),
    },
  },

  set_text: {
    description:
      "Replace the entire contents of the focused input field. Pass an empty string to clear it.",
    inputSchema: {
      text: z.string().max(10000).describe("Replacement text; empty clears the field"),
    },
  },

  press_key: {
    description:
      "Press a system key or perform a global action: back, home, recents, notifications, " +
      "quick_settings, power_dialog, split_screen, lock_screen, take_screenshot.",
    inputSchema: {
      key: z
        .enum([
          "back",
          "home",
          "recents",
          "notifications",
          "quick_settings",
          "power_dialog",
          "split_screen",
          "lock_screen",
          "take_screenshot",
        ])
        .describe("Key or global action"),
    },
  },

  open_app: {
    description:
      "Launch an app by package name, e.g. com.android.chrome. Only apps with a launcher icon can be " +
      "opened. Follow with `wait_for_element` rather than assuming the app is ready.",
    inputSchema: {
      package: z.string().describe("Package name, e.g. com.android.chrome"),
    },
  },

  get_focused: {
    description:
      "Describe the currently focused input element, or report that nothing is focused. Useful for " +
      "confirming a field is ready before typing into it.",
    inputSchema: {},
  },
} as const;
