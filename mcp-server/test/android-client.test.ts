import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import { AndroidClient } from "../src/android-client.js";
import { resolveTarget } from "../src/server.js";
import { encodePairingSecret } from "../src/secure-channel.js";
import { FakeDevice } from "./fake-device.js";

const secret = Buffer.from(Array.from({ length: 32 }, (_, i) => (i * 7) % 256));
const wrongSecret = Buffer.alloc(32, 0x11);

describe("AndroidClient against a device speaking the real protocol", () => {
  let device: FakeDevice;
  let port: number;
  let client: AndroidClient | null = null;

  before(async () => {
    device = new FakeDevice(secret);
    port = await device.start();
  });

  after(async () => {
    client?.disconnect();
    await device.stop();
  });

  beforeEach(() => {
    client?.disconnect();
    client = null;
    device.received.length = 0;
    device.handler = () => ({ ok: true });
  });

  const connect = async (options: { secret?: Buffer } = {}) => {
    client = new AndroidClient({ host: "127.0.0.1", port, secret: options.secret ?? secret });
    return client.connect();
  };

  it("completes the handshake and reports the device's capabilities", async () => {
    const hello = await connect();

    assert.equal(hello.appVersion, "2.0.0-fake");
    assert.equal(hello.protocolVersion, 2);
    assert.equal(hello.encrypted, true);
    assert.deepEqual(hello.capabilities, ["accessibility", "gestures", "screenshot"]);
    assert.equal(client!.connected, true);
  });

  it("sends and receives commands over the encrypted channel", async () => {
    await connect();
    device.handler = (command) => ({ echoed: command });

    const response = await client!.sendCommand("tap", { x: 100, y: 200 });

    assert.equal(response.success, true);
    assert.deepEqual(response.data, { echoed: "tap" });
    assert.deepEqual(device.received[0].params, { x: 100, y: 200 });
  });

  it("surfaces a device-side failure with its error code", async () => {
    await connect();
    device.handler = () => ({ __error: "No element matched" });

    const response = await client!.sendCommand("click_element", { text: "Missing" });

    assert.equal(response.success, false);
    assert.equal(response.error, "No element matched");
    assert.equal(response.error_code, "ACTION_FAILED");
  });

  it("keeps concurrent requests correlated to their own replies", async () => {
    await connect();
    device.handler = (command, params) => ({ command, marker: params.marker });

    const replies = await Promise.all(
      [1, 2, 3, 4, 5].map((marker) => client!.sendCommand("ping", { marker })),
    );

    replies.forEach((reply, index) => {
      assert.equal((reply.data as Record<string, unknown>).marker, index + 1);
    });
  });

  // --------------------------------------------------------------- authentication

  /**
   * The critical one. A wrong secret must be refused during the HTTP upgrade, so the peer
   * never reaches a state where it could send a command. The previous device build checked
   * the token in `onOpen`, after the WebSocket was already established — and never assigned
   * the token at all, so every server ran unauthenticated.
   */
  it("is rejected outright when the pairing secret is wrong", async () => {
    await assert.rejects(
      () => connect({ secret: wrongSecret }),
      (error: Error) => {
        // The message must name the pairing secret. `ws` reports the device's refusal as
        // "Unexpected server response: 404", which reads like a wrong address and sends
        // people debugging the wrong thing.
        assert.match(error.message, /pairing secret/i);
        assert.match(error.message, /404/);
        return true;
      },
    );

    assert.equal(client!.connected, false);
    assert.equal(device.received.length, 0, "no command may reach the device");
  });

  it("cannot send commands after a rejected handshake", async () => {
    await connect({ secret: wrongSecret }).catch(() => undefined);
    await assert.rejects(() => client!.sendCommand("tap", { x: 1, y: 1 }), /Not connected/);
  });

  // ------------------------------------------------------------------ failure modes

  /**
   * This is the regression test for the crash that killed the MCP server process.
   *
   * The previous client extended `EventEmitter` and called `emit("error", …)` from the
   * socket's error handler. Node throws when `"error"` is emitted with no listener attached,
   * and none ever was — so a phone dropping off Wi-Fi threw inside a socket callback,
   * escaped as an uncaught exception and terminated the whole MCP server. Every tool in the
   * user's session died with it.
   *
   * A disconnect must now be an ordinary rejected promise and nothing more.
   */
  it("survives an abrupt mid-session disconnect without an uncaught exception", async () => {
    await connect();

    const uncaught: Error[] = [];
    const onUncaught = (error: Error) => uncaught.push(error);
    process.on("uncaughtException", onUncaught);
    process.on("unhandledRejection", onUncaught as never);

    try {
      const inFlight = client!.sendCommand("get_ui_tree", {}, 5_000);
      device.dropAllConnections();

      await assert.rejects(() => inFlight);
      // Let any stray asynchronous throw reach the process handlers.
      await new Promise((resolve) => setTimeout(resolve, 150));

      assert.deepEqual(uncaught, [], "a disconnect must not produce an uncaught exception");
      assert.equal(client!.connected, false);
    } finally {
      process.off("uncaughtException", onUncaught);
      process.off("unhandledRejection", onUncaught as never);
    }
  });

  it("reports an unreachable host instead of hanging", async () => {
    const unreachable = new AndroidClient({
      host: "127.0.0.1",
      port: 1, // Nothing listens here.
      secret,
      handshakeTimeoutMs: 3_000,
    });

    // ECONNREFUSED must name the actual remedy, not restate the address.
    await assert.rejects(() => unreachable.connect(), /Nothing is listening/);
  });

  it("times out a command that is never answered", async () => {
    await connect();
    // The device accepts the request and never answers.
    device.handler = () => null;

    await assert.rejects(() => client!.sendCommand("tap", { x: 1, y: 1 }, 300), /did not reply/);
  });

  it("notifies its owner on disconnect exactly once", async () => {
    await connect();

    const reasons: string[] = [];
    client!.onDisconnect = (reason) => reasons.push(reason);

    device.dropAllConnections();
    await new Promise((resolve) => setTimeout(resolve, 150));
    client!.disconnect(); // Idempotent: must not fire a second notification.

    assert.equal(reasons.length, 1);
  });
});

describe("AndroidClient protocol negotiation", () => {
  it("refuses a device speaking a different protocol version", async () => {
    const device = new FakeDevice(secret, { protocolVersion: 99 });
    const port = await device.start();

    try {
      const client = new AndroidClient({ host: "127.0.0.1", port, secret });
      await assert.rejects(() => client.connect(), /Protocol mismatch/);
    } finally {
      await device.stop();
    }
  });

  it("fails clearly when the device never sends a session frame", async () => {
    const device = new FakeDevice(secret, { omitSessionFrame: true });
    const port = await device.start();

    try {
      const client = new AndroidClient({ host: "127.0.0.1", port, secret, handshakeTimeoutMs: 1_000 });
      await assert.rejects(() => client.connect(), /timed out/);
    } finally {
      await device.stop();
    }
  });
});

describe("resolveTarget", () => {
  const encoded = encodePairingSecret(secret);

  it("accepts a pairing URI", () => {
    const target = resolveTarget({ pairingUri: `droidpilot://10.0.0.5:8765#${encoded}` });
    assert.deepEqual(target, { host: "10.0.0.5", port: 8765, secret });
  });

  it("accepts discrete host, port and secret", () => {
    const target = resolveTarget({ host: "10.0.0.5", port: 9000, secret: encoded });
    assert.deepEqual(target, { host: "10.0.0.5", port: 9000, secret });
  });

  it("defaults the port", () => {
    const target = resolveTarget({ host: "10.0.0.5", secret: encoded });
    assert.equal("port" in target && target.port, 8765);
  });

  /**
   * There is deliberately no unauthenticated path. Connecting without a secret must be
   * impossible to express, not merely discouraged.
   */
  it("refuses to connect without a secret", () => {
    const target = resolveTarget({ host: "10.0.0.5" });
    assert.ok("error" in target);
    assert.match(target.error, /pairing secret is required/i);
  });

  it("explains a malformed secret", () => {
    const target = resolveTarget({ host: "10.0.0.5", secret: "too-short" });
    assert.ok("error" in target);
    assert.match(target.error, /43 URL-safe base64/);
  });

  it("explains a malformed pairing URI", () => {
    const target = resolveTarget({ pairingUri: "https://example.com" });
    assert.ok("error" in target);
    assert.match(target.error, /pairing URI/);
  });

  it("requires at least a host", () => {
    const target = resolveTarget({});
    assert.ok("error" in target);
  });
});
