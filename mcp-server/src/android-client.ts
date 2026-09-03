import WebSocket from "ws";
import { SALT_BYTES, SecureChannel } from "./secure-channel.js";

export interface CommandResponse {
  id: string;
  success: boolean;
  data?: Record<string, unknown> | null;
  error?: string;
  error_code?: string;
}

export interface DeviceHello {
  protocolVersion: number;
  appVersion: string;
  encrypted: boolean;
  capabilities: string[];
}

export interface AndroidClientOptions {
  host: string;
  port: number;
  secret: Buffer;
  /** Overall budget for connect, key agreement and the device's hello. */
  handshakeTimeoutMs?: number;
}

/** Protocol version this client speaks. Must match `Protocol.VERSION` on the device. */
export const CLIENT_PROTOCOL_VERSION = 2;

const DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000;
const DEFAULT_COMMAND_TIMEOUT_MS = 30_000;

/**
 * Transport to a paired Android device.
 *
 * ## Why this does not extend EventEmitter
 *
 * The previous implementation did, and called `this.emit("error", err)` from the socket's
 * error handler. Node's `EventEmitter` **throws** when `"error"` is emitted with no
 * listener registered, and nothing in the MCP server ever registered one — so the moment
 * the phone dropped off Wi-Fi, the emit threw inside a socket callback, escaped as an
 * uncaught exception, and killed the whole MCP server process. From the user's side an
 * unreachable phone silently took down every tool in their session.
 *
 * Errors are delivered here as ordinary values: a rejected promise for the caller who
 * asked, and an optional plain callback for state changes. There is no path by which a
 * transport error can terminate the process.
 */
export class AndroidClient {
  private ws: WebSocket | null = null;
  private channel: SecureChannel | null = null;
  private hello: DeviceHello | null = null;
  private requestCounter = 0;
  private closed = false;

  private readonly pending = new Map<
    string,
    { resolve: (value: CommandResponse) => void; reject: (reason: Error) => void; timer: NodeJS.Timeout }
  >();

  /** Called on disconnect. Never throws into the socket callback. */
  onDisconnect?: (reason: string) => void;

  constructor(private readonly options: AndroidClientOptions) {}

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN && this.channel !== null;
  }

  get deviceInfo(): DeviceHello | null {
    return this.hello;
  }

  get url(): string {
    return `ws://${this.options.host}:${this.options.port}`;
  }

  /**
   * Connects, authenticates and establishes the encrypted channel.
   *
   * Resolves only once the device's `hello` has been received and decrypted, so a resolved
   * promise means the secret was correct and the channel works — not merely that a TCP
   * connection opened.
   */
  async connect(): Promise<DeviceHello> {
    const timeoutMs = this.options.handshakeTimeoutMs ?? DEFAULT_HANDSHAKE_TIMEOUT_MS;

    return new Promise<DeviceHello>((resolve, reject) => {
      let settled = false;
      const finish = (error: Error | null, hello?: DeviceHello) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (error) {
          this.teardown(error.message);
          reject(error);
        } else {
          resolve(hello!);
        }
      };

      const timer = setTimeout(
        () => finish(new Error(`Handshake with ${this.url} timed out after ${timeoutMs} ms`)),
        timeoutMs,
      );

      let socket: WebSocket;
      try {
        socket = new WebSocket(this.url, {
          headers: {
            // Presented during the HTTP upgrade, so the device rejects an unauthenticated
            // client before the WebSocket exists rather than after.
            Authorization: `Bearer ${this.options.secret.toString("base64url")}`,
          },
          handshakeTimeout: timeoutMs,
          // Bounds memory against a malicious or malfunctioning peer. Screenshots are the
          // largest legitimate payload and are capped well below this on the device.
          maxPayload: 16 * 1024 * 1024,
        });
      } catch (error) {
        finish(error instanceof Error ? error : new Error(String(error)));
        return;
      }

      this.ws = socket;
      this.closed = false;

      socket.on("message", (data, isBinary) => {
        try {
          if (!isBinary) {
            // The only plaintext frame in the protocol is the session salt.
            this.handleSessionFrame(data.toString());
            return;
          }

          const plaintext = this.decrypt(toBuffer(data));
          if (!plaintext) {
            finish(new Error("A frame from the device failed authentication"));
            return;
          }

          const message = JSON.parse(plaintext.toString("utf8"));
          if (message?.type === "hello") {
            this.hello = message as DeviceHello;
            finish(null, this.hello);
            return;
          }
          this.resolvePending(message as CommandResponse);
        } catch (error) {
          finish(error instanceof Error ? error : new Error(String(error)));
        }
      });

      socket.on("close", (code, reasonBuffer) => {
        const reason = describeClose(code, reasonBuffer.toString());
        finish(new Error(reason));
        this.teardown(reason);
      });

      // A plain value-returning handler. This is the crash that used to be here.
      socket.on("error", (error) => {
        finish(new Error(describeConnectError(this.url, error.message)));
      });
    });
  }

  private handleSessionFrame(raw: string): void {
    const message = JSON.parse(raw);
    if (message?.type !== "session" || typeof message.salt !== "string") {
      throw new Error("Device did not open with a session frame; is it running DroidPilot 2.x?");
    }

    if (typeof message.protocolVersion === "number" && message.protocolVersion !== CLIENT_PROTOCOL_VERSION) {
      throw new Error(
        `Protocol mismatch: the device speaks v${message.protocolVersion}, this client speaks ` +
          `v${CLIENT_PROTOCOL_VERSION}. Update whichever is older.`,
      );
    }

    const salt = Buffer.from(message.salt, "base64");
    if (salt.length !== SALT_BYTES) {
      throw new Error(`Device sent a ${salt.length}-byte session salt; expected ${SALT_BYTES}`);
    }

    this.channel = SecureChannel.derive(this.options.secret, salt);
  }

  private decrypt(record: Buffer): Buffer | null {
    if (!this.channel) throw new Error("Received an encrypted frame before the session was established");
    return this.channel.open(record);
  }

  private resolvePending(response: CommandResponse): void {
    const pending = response.id ? this.pending.get(response.id) : undefined;
    if (!pending) return; // A late reply to a request that already timed out.
    clearTimeout(pending.timer);
    this.pending.delete(response.id);
    pending.resolve(response);
  }

  /**
   * Sends a command and waits for its reply.
   *
   * Rejects rather than throwing asynchronously, so every failure reaches the caller that
   * caused it and none of them can escape as an uncaught exception.
   */
  async sendCommand(
    command: string,
    params: Record<string, unknown> = {},
    timeoutMs: number = DEFAULT_COMMAND_TIMEOUT_MS,
  ): Promise<CommandResponse> {
    if (!this.connected || !this.ws || !this.channel) {
      throw new Error("Not connected to a device. Use the 'connect' tool first.");
    }

    const id = `req_${++this.requestCounter}_${Date.now()}`;
    // The timestamp lets the device reject a stale or replayed privileged request. It is
    // optional for ordinary commands and mandatory for shell ones, so it is always sent
    // rather than conditionally: one code path, and no way to forget it where it counts.
    const payload = Buffer.from(
      JSON.stringify({ id, command, params, timestamp: Date.now() }),
      "utf8",
    );

    return new Promise<CommandResponse>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`'${command}' did not reply within ${timeoutMs} ms`));
      }, timeoutMs);

      this.pending.set(id, { resolve, reject, timer });

      try {
        this.ws!.send(this.channel!.seal(payload), (error) => {
          if (error) {
            clearTimeout(timer);
            this.pending.delete(id);
            reject(new Error(`Failed to send '${command}': ${error.message}`));
          }
        });
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  disconnect(): void {
    this.teardown("Disconnected by the client");
  }

  /**
   * Releases every resource and fails outstanding requests.
   *
   * Idempotent, because it is reachable from `close`, `error` and an explicit disconnect —
   * potentially all three for a single dropped connection.
   */
  private teardown(reason: string): void {
    if (this.closed) return;
    this.closed = true;

    for (const [, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new Error(reason));
    }
    this.pending.clear();

    this.channel = null;

    if (this.ws) {
      this.ws.removeAllListeners();
      if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
        this.ws.terminate();
      }
      this.ws = null;
    }

    this.onDisconnect?.(reason);
  }
}

function toBuffer(data: WebSocket.RawData): Buffer {
  if (Buffer.isBuffer(data)) return data;
  if (Array.isArray(data)) return Buffer.concat(data);
  return Buffer.from(data);
}

/**
 * Turns a failed HTTP upgrade into something a user can act on.
 *
 * A rejected pairing secret does not arrive as a WebSocket close code, because the device
 * rejects it *during* the HTTP upgrade — before any WebSocket exists. Java-WebSocket, which
 * the device uses, answers a refused handshake with a bare `HTTP/1.1 404 WebSocket Upgrade
 * Failure` (verified against the library, and asserted by the fake device in the tests).
 *
 * So the wrong secret — overwhelmingly the most common setup mistake — surfaces to `ws` as
 * "Unexpected server response: 404", which reads like a wrong address and sends people
 * hunting for the wrong problem. Naming the likely cause is the difference between a
 * two-minute fix and a bug report.
 */
function describeConnectError(url: string, message: string): string {
  const status = /Unexpected server response: (\d{3})/.exec(message)?.[1];

  if (status === "404" || status === "401" || status === "403") {
    return (
      `The device refused the connection (HTTP ${status}). This almost always means the pairing ` +
      `secret is wrong or was truncated — copy it again from the DroidPilot app. It can also mean ` +
      `the device has locked this client out after repeated failed attempts; if so, wait a few minutes.`
    );
  }

  if (message.includes("ECONNREFUSED")) {
    return `Nothing is listening on ${url}. Check the port, and that you tapped “Start server” in the DroidPilot app.`;
  }

  if (message.includes("EHOSTUNREACH") || message.includes("ENETUNREACH") || message.includes("ETIMEDOUT")) {
    return `${url} is unreachable. Check that the device is on the same network and awake.`;
  }

  return `Could not reach ${url}: ${message}`;
}

/** Turns a close code into something a user can act on. */
function describeClose(code: number, reason: string): string {
  const detail = reason ? `: ${reason}` : "";
  switch (code) {
    case 1008:
      return `The device closed the connection${detail}. A frame failed authentication, which means the channel is no longer trustworthy.`;
    case 1013:
      return `The device is refusing new connections${detail}. It may already have the maximum number of clients.`;
    case 1006:
      return "The connection dropped without a close handshake. The device may have left the network, or the server was stopped.";
    default:
      return `Connection closed (${code})${detail}`;
  }
}
