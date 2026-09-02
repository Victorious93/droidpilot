import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  SecureChannel,
  decodePairingSecret,
  encodePairingSecret,
  parsePairingUri,
} from "../src/secure-channel.js";

const secret = Buffer.from(Array.from({ length: 32 }, (_, i) => i));
const salt = Buffer.from(Array.from({ length: 16 }, (_, i) => 0xa0 + i));

const pair = () => ({
  client: SecureChannel.derive(secret, salt, "client"),
  device: SecureChannel.derive(secret, salt, "server"),
});

describe("SecureChannel", () => {
  it("carries messages in both directions", () => {
    const { client, device } = pair();

    const request = Buffer.from('{"id":"1","command":"ping"}');
    assert.deepEqual(device.open(client.seal(request)), request);

    const response = Buffer.from('{"id":"1","success":true}');
    assert.deepEqual(client.open(device.seal(response)), response);
  });

  it("carries many records in sequence", () => {
    const { client, device } = pair();
    for (let i = 0; i < 500; i++) {
      const payload = Buffer.from(`record-${i}`);
      assert.deepEqual(device.open(client.seal(payload)), payload);
    }
  });

  // A record is a device command, so a replayed `tap` is a second real tap.
  it("rejects a replayed record", () => {
    const { client, device } = pair();
    const record = client.seal(Buffer.from("tap"));

    assert.notEqual(device.open(record), null);
    assert.equal(device.open(Buffer.from(record)), null);
  });

  it("rejects records delivered out of order", () => {
    const { client, device } = pair();
    const first = client.seal(Buffer.from("first"));
    const second = client.seal(Buffer.from("second"));

    assert.notEqual(device.open(second), null);
    assert.equal(device.open(first), null);
  });

  it("detects tampering with ciphertext, tag and nonce", () => {
    const { client, device } = pair();
    const record = client.seal(Buffer.from("payload of some length"));

    for (const index of [4, 14, record.length - 1]) {
      const tampered = Buffer.from(record);
      tampered[index] ^= 0x01;
      assert.equal(device.open(tampered), null, `byte ${index} should have been detected`);
    }
  });

  it("rejects truncated records without throwing", () => {
    const { client, device } = pair();
    const record = client.seal(Buffer.from("payload"));

    for (let length = 0; length < record.length; length++) {
      assert.equal(device.open(record.subarray(0, length)), null);
    }
  });

  // Without direction separation a device response could be echoed back and accepted.
  it("rejects a record reflected back at its sender", () => {
    const { device } = pair();
    assert.equal(device.open(device.seal(Buffer.from("response"))), null);
  });

  it("cannot be decrypted with the wrong secret", () => {
    const client = SecureChannel.derive(secret, salt, "client");
    const wrong = SecureChannel.derive(Buffer.alloc(32, 9), salt, "server");
    assert.equal(wrong.open(client.seal(Buffer.from("payload"))), null);
  });

  it("cannot be decrypted with a different session salt", () => {
    const client = SecureChannel.derive(secret, salt, "client");
    const other = SecureChannel.derive(secret, Buffer.alloc(16, 7), "server");
    assert.equal(other.open(client.seal(Buffer.from("payload"))), null);
  });

  it("round-trips empty and large payloads", () => {
    const { client, device } = pair();
    assert.deepEqual(device.open(client.seal(Buffer.alloc(0))), Buffer.alloc(0));

    const large = Buffer.alloc(2 * 1024 * 1024, 0x5a);
    assert.deepEqual(device.open(client.seal(large)), large);
  });

  it("rejects wrongly sized secrets and salts", () => {
    assert.throws(() => SecureChannel.derive(Buffer.alloc(16), salt));
    assert.throws(() => SecureChannel.derive(secret, Buffer.alloc(8)));
  });

  /**
   * The other half of the cross-implementation pin.
   *
   * `SecureChannelTest.kt` asserts these exact bytes too. The first record of a session is
   * fully determined by (secret, salt, plaintext), so if either implementation changes its
   * key schedule, nonce layout or framing, one of the two suites fails here rather than
   * the two silently failing to talk to each other on a user's device.
   */
  it("matches the shared cross-implementation vector", () => {
    const expected = Buffer.from(
      "9f4eb382000000000000000094e5abbff9488314ea531144d012dde44179a648" +
        "e7686d9c0522ad43b7f295cd765d7ef498025e74885f471ea21ddfb336bf",
      "hex",
    );
    const plaintext = Buffer.from('{"id":"vector-1","command":"ping"}');

    assert.deepEqual(SecureChannel.derive(secret, salt, "client").seal(plaintext), expected);
    assert.deepEqual(SecureChannel.derive(secret, salt, "server").open(expected), plaintext);
  });
});

describe("pairing secret encoding", () => {
  it("round-trips", () => {
    assert.deepEqual(decodePairingSecret(encodePairingSecret(secret)), secret);
  });

  it("is url-safe and unpadded", () => {
    const encoded = encodePairingSecret(secret);
    assert.equal(encoded.length, 43);
    assert.ok(!/[+/=]/.test(encoded));
  });

  it("tolerates surrounding whitespace", () => {
    assert.deepEqual(decodePairingSecret(`  ${encodePairingSecret(secret)}\n`), secret);
  });

  it("returns null for malformed or wrongly sized input", () => {
    for (const bad of ["", "!!!", "abc", Buffer.alloc(16).toString("base64url")]) {
      assert.equal(decodePairingSecret(bad), null, `'${bad}' should not decode`);
    }
  });
});

describe("parsePairingUri", () => {
  it("parses a well-formed URI", () => {
    const encoded = encodePairingSecret(secret);
    const parsed = parsePairingUri(`droidpilot://192.168.1.42:8765#${encoded}`);

    assert.deepEqual(parsed, { host: "192.168.1.42", port: 8765, secret });
  });

  it("tolerates surrounding whitespace", () => {
    const uri = `  droidpilot://10.0.0.2:9000#${encodePairingSecret(secret)}  `;
    assert.equal(parsePairingUri(uri)?.port, 9000);
  });

  it("rejects malformed URIs", () => {
    const encoded = encodePairingSecret(secret);
    const bad = [
      "",
      "not a uri",
      "http://192.168.1.42:8765#" + encoded,
      "droidpilot://192.168.1.42#" + encoded, // no port
      "droidpilot://192.168.1.42:8765", // no secret
      "droidpilot://192.168.1.42:8765#short",
      "droidpilot://192.168.1.42:99999#" + encoded, // port out of range
      "droidpilot://192.168.1.42:0#" + encoded,
    ];
    for (const uri of bad) {
      assert.equal(parsePairingUri(uri), null, `'${uri}' should not parse`);
    }
  });
});
