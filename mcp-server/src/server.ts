import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { AndroidClient } from "./android-client.js";
import { decodePairingSecret, parsePairingUri } from "./secure-channel.js";
import { toolDefinitions } from "./tools.js";

type TextContent = { type: "text"; text: string };
type ImageContent = { type: "image"; data: string; mimeType: string };

/**
 * `isError` is the field the previous implementation never set.
 *
 * Without it every failure came back as an ordinary successful tool call whose text
 * happened to begin "Error:", so the model had to infer failure from prose — and would
 * cheerfully carry on as though a tap it never performed had succeeded.
 */
type ToolResult = { content: (TextContent | ImageContent)[]; isError?: boolean };

function ok(text: string): ToolResult {
  return { content: [{ type: "text", text }] };
}

function fail(text: string): ToolResult {
  return { content: [{ type: "text", text }], isError: true };
}

/**
 * How long this client waits for a shell reply.
 *
 * Always longer than the timeout the device is being asked to enforce, so that a command
 * which the device times out cleanly reports as a timeout *from the device* — with its
 * partial output and audit record — rather than as this client giving up first and leaving
 * the caller unable to tell whether anything ran.
 */
function shellTimeoutFor(params: { timeout?: number }): number {
  return (params.timeout ?? 30_000) + 20_000;
}

export function createServer(): McpServer {
  const server = new McpServer({ name: "droidpilot", version: "2.0.0" });

  let client: AndroidClient | null = null;

  /**
   * Runs a device command, turning every failure mode into an `isError` result.
   *
   * Throwing out of an MCP handler produces an opaque protocol-level error; returning a
   * described failure lets the model read what went wrong and choose a different approach.
   */
  async function send(
    command: string,
    params: Record<string, unknown> = {},
    timeoutMs?: number,
  ): Promise<ToolResult> {
    if (!client?.connected) {
      return fail("Not connected to a device. Use the 'connect' tool first.");
    }

    let response;
    try {
      response = await client.sendCommand(command, params, timeoutMs);
    } catch (error) {
      return fail(`${command} failed: ${(error as Error).message}`);
    }

    if (!response.success) {
      // The device's own error code is included: it is stable and machine-readable, so a
      // model can distinguish "element not found, try another selector" from
      // "this device cannot take screenshots, stop asking".
      const code = response.error_code ? ` [${response.error_code}]` : "";
      return fail(`${command} failed${code}: ${response.error ?? "unknown error"}`);
    }

    if (command === "screenshot" && response.data?.image) {
      const { image, width, height, format, scaled } = response.data as Record<string, unknown>;
      return {
        content: [
          { type: "image", data: image as string, mimeType: `image/${format ?? "jpeg"}` },
          {
            type: "text",
            text: `Screenshot ${width}×${height}${scaled ? " (downscaled)" : ""}`,
          },
        ],
      };
    }

    return ok(JSON.stringify(response.data ?? {}, null, 2));
  }

  // ------------------------------------------------------------------ connection

  server.tool(
    "connect",
    toolDefinitions.connect.description,
    toolDefinitions.connect.inputSchema,
    async ({ pairingUri, host, port, secret }): Promise<ToolResult> => {
      const target = resolveTarget({ pairingUri, host, port, secret });
      if ("error" in target) return fail(target.error);

      client?.disconnect();
      client = new AndroidClient(target);

      try {
        const hello = await client.connect();
        return ok(
          [
            `Connected to ${target.host}:${target.port}`,
            `DroidPilot ${hello.appVersion} (protocol v${hello.protocolVersion}), channel encrypted.`,
            hello.capabilities.length > 0
              ? `Capabilities: ${hello.capabilities.join(", ")}`
              : "The device reports no capabilities — the Accessibility service is probably off.",
          ].join("\n"),
        );
      } catch (error) {
        client = null;
        return fail(`Could not connect to ${target.host}:${target.port} — ${(error as Error).message}`);
      }
    },
  );

  server.tool("disconnect", toolDefinitions.disconnect.description, {}, async (): Promise<ToolResult> => {
    client?.disconnect();
    client = null;
    return ok("Disconnected");
  });

  // --------------------------------------------------------------------- reading

  server.tool("get_device_info", toolDefinitions.get_device_info.description, {}, () =>
    send("get_device_info"),
  );

  server.tool("get_ui_tree", toolDefinitions.get_ui_tree.description, toolDefinitions.get_ui_tree.inputSchema, (p) =>
    send("get_ui_tree", p),
  );

  server.tool("find_element", toolDefinitions.find_element.description, toolDefinitions.find_element.inputSchema, (p) =>
    send("find_element", p),
  );

  server.tool("get_focused", toolDefinitions.get_focused.description, {}, () => send("get_focused"));

  // Shell tools declare initiator "ai", which is the truth: every command that reaches this
  // server was chosen by a model. That is what makes the device's AI_ROOT gate meaningful —
  // an owner who grants REMOTE_ROOT but withholds AI_ROOT will find that root commands from
  // here are refused, which is exactly what withholding it is supposed to mean.
  server.tool(
    "shell",
    toolDefinitions.shell.description,
    toolDefinitions.shell.inputSchema,
    (p) => send("shell", { ...p, initiator: "ai" }, shellTimeoutFor(p)),
  );

  server.tool(
    "shell_root",
    toolDefinitions.shell_root.description,
    toolDefinitions.shell_root.inputSchema,
    (p) => send("shell_root", { ...p, initiator: "ai" }, shellTimeoutFor(p)),
  );

  server.tool("screenshot", toolDefinitions.screenshot.description, toolDefinitions.screenshot.inputSchema, (p) =>
    send("screenshot", p, 30_000),
  );

  // --------------------------------------------------------------------- acting

  server.tool(
    "click_element",
    toolDefinitions.click_element.description,
    toolDefinitions.click_element.inputSchema,
    (p) => send("click_element", p),
  );

  server.tool(
    "long_click_element",
    toolDefinitions.long_click_element.description,
    toolDefinitions.long_click_element.inputSchema,
    (p) => send("long_click_element", p),
  );

  server.tool(
    "wait_for_element",
    toolDefinitions.wait_for_element.description,
    toolDefinitions.wait_for_element.inputSchema,
    (p) =>
      // The client's own deadline must outlast the device's, or the transport gives up
      // first and reports a timeout for a wait that was proceeding normally.
      send("wait_for_element", p, (p.timeout ?? 10_000) + 15_000),
  );

  server.tool("tap", toolDefinitions.tap.description, toolDefinitions.tap.inputSchema, (p) => send("tap", p));

  server.tool("long_press", toolDefinitions.long_press.description, toolDefinitions.long_press.inputSchema, (p) =>
    send("long_press", p),
  );

  server.tool("swipe", toolDefinitions.swipe.description, toolDefinitions.swipe.inputSchema, (p) => send("swipe", p));

  server.tool("scroll", toolDefinitions.scroll.description, toolDefinitions.scroll.inputSchema, (p) =>
    send("scroll", p),
  );

  server.tool("pinch", toolDefinitions.pinch.description, toolDefinitions.pinch.inputSchema, (p) => send("pinch", p));

  server.tool("type_text", toolDefinitions.type_text.description, toolDefinitions.type_text.inputSchema, (p) =>
    send("type_text", p),
  );

  server.tool("set_text", toolDefinitions.set_text.description, toolDefinitions.set_text.inputSchema, (p) =>
    send("set_text", p),
  );

  server.tool("press_key", toolDefinitions.press_key.description, toolDefinitions.press_key.inputSchema, (p) =>
    send("press_key", p),
  );

  server.tool("open_app", toolDefinitions.open_app.description, toolDefinitions.open_app.inputSchema, (p) =>
    send("open_app", { package: p.package }),
  );

  return server;
}

/**
 * Resolves the connection target from either a pairing URI or discrete fields.
 *
 * Exported for tests: this is pure input handling and it is where a user's mistake — a
 * truncated secret, a URI pasted into the wrong field — should turn into an explanation
 * rather than a failed connection with no clue why.
 */
export function resolveTarget(input: {
  pairingUri?: string;
  host?: string;
  port?: number;
  secret?: string;
}): { host: string; port: number; secret: Buffer } | { error: string } {
  if (input.pairingUri) {
    const parsed = parsePairingUri(input.pairingUri);
    if (!parsed) {
      return {
        error:
          "That does not look like a DroidPilot pairing URI. It should be " +
          "droidpilot://<host>:<port>#<secret> — use “Copy pairing URI” in the app.",
      };
    }
    return parsed;
  }

  if (!input.host) {
    return { error: "Provide either `pairingUri`, or `host` and `secret`." };
  }
  if (!input.secret) {
    return {
      error:
        "A pairing secret is required. DroidPilot refuses unauthenticated connections. " +
        "Find it under “Pairing secret” in the app.",
    };
  }

  const secret = decodePairingSecret(input.secret);
  if (!secret) {
    return {
      error:
        "The pairing secret is malformed. It should be 43 URL-safe base64 characters — " +
        "copy it again from the app, making sure nothing was truncated.",
    };
  }

  return { host: input.host, port: input.port ?? 8765, secret };
}
