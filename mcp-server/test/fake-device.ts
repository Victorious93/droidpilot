import { timingSafeEqual } from "node:crypto";
import { AddressInfo } from "node:net";
import { WebSocket, WebSocketServer } from "ws";
import { SecureChannel, decodePairingSecret, randomSalt } from "../src/secure-channel.js";

/**
 * A stand-in for an Android device running DroidPilot, speaking the real wire protocol.
 *
 * It exists so the client's handshake, authentication and disconnect behaviour can be
 * tested end-to-end over a real socket without a phone. That coverage is the point: the
 * defects being guarded against here — an unauthenticated peer reaching the command
 * handler, and a mid-session drop taking down the process — only appear once there is an
 * actual connection to break.
 *
 * It mirrors `ControlServer.kt`: authenticate during the HTTP upgrade, send the session
 * salt in the clear, then send an encrypted hello.
 */
export class FakeDevice {
  private server: WebSocketServer | null = null;
  private readonly channels = new Map<WebSocket, SecureChannel>();

  /** Commands received, so tests can assert what the client actually sent. */
  readonly received: Array<{
    id: string;
    command: string;
    params: Record<string, unknown>;
    timestamp?: number;
  }> = [];

  /**
   * Replaceable per test to script a specific reply.
   *
   * Returning `null` means "accept the request and never answer", which is how the client's
   * command timeout is exercised. That is a return value rather than a thrown error on
   * purpose: a throw here escapes the socket callback as an uncaught exception and takes
   * down the test runner, which is exactly the class of failure this fake exists to help
   * catch in the product code.
   */
  handler: (
    command: string,
    params: Record<string, unknown>,
  ) => Record<string, unknown> | { __error: string } | null = () => ({ ok: true });

  constructor(
    private readonly secret: Buffer,
    private readonly options: {
      protocolVersion?: number;
      capabilities?: string[];
      /** Skips the session frame, simulating an old or wrong server. */
      omitSessionFrame?: boolean;
    } = {},
  ) {}

  async start(): Promise<number> {
    return new Promise((resolve) => {
      this.server = new WebSocketServer(
        {
          port: 0,
          host: "127.0.0.1",
          // Authentication happens during the HTTP upgrade — before a WebSocket exists —
          // exactly as `onWebsocketHandshakeReceivedAsServer` does on the device. Checking
          // after the connection opens is the bug this shape prevents.
          verifyClient: ({ req }, done) => {
            const header = req.headers["authorization"];
            const presented =
              typeof header === "string" && header.startsWith("Bearer ")
                ? decodePairingSecret(header.slice("Bearer ".length))
                : null;

            const authorised =
              presented !== null &&
              presented.length === this.secret.length &&
              timingSafeEqual(presented, this.secret);

            // 404 mirrors the real device exactly. Java-WebSocket answers a handshake
            // rejected in `onWebsocketHandshakeReceivedAsServer` with a bare
            // `HTTP/1.1 404 WebSocket Upgrade Failure` — verified directly against the
            // library. Using a friendlier status here would make this fake easier to
            // satisfy than the thing it stands in for, which is the one property a fake
            // must never have.
            done(authorised, 404, "WebSocket Upgrade Failure");
          },
        },
        () => resolve((this.server!.address() as AddressInfo).port),
      );

      this.server.on("connection", (socket) => this.onConnection(socket));
    });
  }

  private onConnection(socket: WebSocket): void {
    const salt = randomSalt();
    const channel = SecureChannel.derive(this.secret, salt, "server");
    this.channels.set(socket, channel);

    if (!this.options.omitSessionFrame) {
      socket.send(
        JSON.stringify({
          type: "session",
          salt: salt.toString("base64"),
          protocolVersion: this.options.protocolVersion ?? 2,
        }),
      );

      socket.send(
        channel.seal(
          Buffer.from(
            JSON.stringify({
              type: "hello",
              protocolVersion: this.options.protocolVersion ?? 2,
              appVersion: "2.0.0-fake",
              encrypted: true,
              capabilities: this.options.capabilities ?? ["accessibility", "gestures", "screenshot"],
            }),
          ),
        ),
      );
    }

    socket.on("message", (data, isBinary) => {
      if (!isBinary) return;
      const plaintext = channel.open(Buffer.from(data as Buffer));
      if (!plaintext) {
        socket.close(1008, "Record authentication failed");
        return;
      }

      const request = JSON.parse(plaintext.toString("utf8"));
      this.received.push(request);

      // A misbehaving handler must fail its own test, never the whole run.
      let result: Record<string, unknown> | { __error: string } | null;
      try {
        result = this.handler(request.command, request.params ?? {});
      } catch (error) {
        result = { __error: `fake device handler threw: ${(error as Error).message}` };
      }

      if (result === null) return; // Deliberately silent: drives the client's timeout path.

      const response =
        "__error" in result
          ? { id: request.id, success: false, error: result.__error, error_code: "ACTION_FAILED" }
          : { id: request.id, success: true, data: result };

      socket.send(channel.seal(Buffer.from(JSON.stringify(response))));
    });

    socket.on("close", () => this.channels.delete(socket));
  }

  /** Severs every connection abruptly, with no close handshake — a phone leaving Wi-Fi. */
  dropAllConnections(): void {
    for (const socket of this.channels.keys()) socket.terminate();
    this.channels.clear();
  }

  /** Sends a frame the client cannot authenticate, simulating corruption or an attacker. */
  sendGarbage(): void {
    for (const socket of this.channels.keys()) socket.send(Buffer.alloc(64, 0xff));
  }

  async stop(): Promise<void> {
    this.dropAllConnections();
    await new Promise<void>((resolve) => {
      if (!this.server) return resolve();
      this.server.close(() => resolve());
    });
    this.server = null;
  }
}
