import { createCipheriv, createDecipheriv, hkdfSync, randomBytes, timingSafeEqual } from "node:crypto";

/**
 * Client half of the DroidPilot secure channel.
 *
 * This mirrors `SecureChannel.kt` on the device exactly, and the two are pinned together
 * by shared test vectors in `test/secure-channel.test.ts` and `SecureChannelTest.kt`: both
 * sides assert that a fixed (secret, salt, plaintext) triple produces one specific
 * ciphertext. A change to either implementation that breaks compatibility fails a test
 * rather than silently failing to connect in the field.
 *
 * See the Kotlin file for the rationale and the construction; the summary is HKDF-SHA256
 * for the key schedule, AES-256-GCM per record, a 4-byte direction prefix plus an 8-byte
 * counter for the nonce, and strictly increasing counters to reject replays.
 */

export const SECRET_BYTES = 32;
export const SALT_BYTES = 16;

const NONCE_BYTES = 12;
const IV_PREFIX_BYTES = 4;
// The remaining 8 nonce bytes are the record counter, written by `writeBigUInt64BE`.
const TAG_BYTES = 16;
const KEY_BYTES = 32;

/** Domain-separation label. Must match `SecureChannel.HKDF_INFO` on the device. */
const HKDF_INFO = "droidpilot/v2/keys";

interface DirectionKeys {
  key: Buffer;
  ivPrefix: Buffer;
}

export class SecureChannel {
  private sendCounter = 0n;
  private highestSeenReceiveCounter = -1n;

  private constructor(
    private readonly send: DirectionKeys,
    private readonly receive: DirectionKeys,
  ) {}

  /**
   * Derives a view of the channel from the pairing secret and the per-session salt.
   *
   * `role` defaults to `"client"`, which is what the MCP server always uses. The `"server"`
   * role exists so tests can stand up a fake device that speaks the real protocol — that
   * is how the handshake, authentication rejection and mid-session disconnect paths are
   * covered without a physical phone.
   */
  static derive(secret: Buffer, salt: Buffer, role: "client" | "server" = "client"): SecureChannel {
    if (secret.length !== SECRET_BYTES) {
      throw new Error(`Pairing secret must be ${SECRET_BYTES} bytes, got ${secret.length}`);
    }
    if (salt.length !== SALT_BYTES) {
      throw new Error(`Session salt must be ${SALT_BYTES} bytes, got ${salt.length}`);
    }

    const total = 2 * (KEY_BYTES + IV_PREFIX_BYTES);
    const material = Buffer.from(
      hkdfSync("sha256", secret, salt, Buffer.from(HKDF_INFO, "utf8"), total),
    );

    let offset = 0;
    const take = (n: number): Buffer => {
      const slice = material.subarray(offset, offset + n);
      offset += n;
      return slice;
    };

    const c2sKey = take(KEY_BYTES);
    const c2sIv = take(IV_PREFIX_BYTES);
    const s2cKey = take(KEY_BYTES);
    const s2cIv = take(IV_PREFIX_BYTES);

    // The client sends on c2s and receives on s2c; the device's view is the mirror image.
    return role === "client"
      ? new SecureChannel({ key: c2sKey, ivPrefix: c2sIv }, { key: s2cKey, ivPrefix: s2cIv })
      : new SecureChannel({ key: s2cKey, ivPrefix: s2cIv }, { key: c2sKey, ivPrefix: c2sIv });
  }

  /** Encrypts `plaintext` into a self-contained record: nonce ‖ ciphertext ‖ tag. */
  seal(plaintext: Buffer): Buffer {
    const counter = this.sendCounter;
    this.sendCounter += 1n;

    const nonce = buildNonce(this.send.ivPrefix, counter);
    const cipher = createCipheriv("aes-256-gcm", this.send.key, nonce, {
      authTagLength: TAG_BYTES,
    });
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    return Buffer.concat([nonce, ciphertext, cipher.getAuthTag()]);
  }

  /**
   * Verifies and decrypts a record from the device.
   *
   * Returns `null` for a record that is truncated, carries the wrong IV prefix, replays a
   * counter, or fails authentication. Callers must drop the connection rather than retry:
   * any of those means the stream is no longer trustworthy.
   */
  open(record: Buffer): Buffer | null {
    if (record.length < NONCE_BYTES + TAG_BYTES) return null;

    const nonce = record.subarray(0, NONCE_BYTES);
    const prefix = nonce.subarray(0, IV_PREFIX_BYTES);
    if (prefix.length !== this.receive.ivPrefix.length || !timingSafeEqual(prefix, this.receive.ivPrefix)) {
      return null;
    }

    const counter = readCounter(nonce);
    if (counter <= this.highestSeenReceiveCounter) return null;

    const tag = record.subarray(record.length - TAG_BYTES);
    const ciphertext = record.subarray(NONCE_BYTES, record.length - TAG_BYTES);

    try {
      const decipher = createDecipheriv("aes-256-gcm", this.receive.key, nonce, {
        authTagLength: TAG_BYTES,
      });
      decipher.setAuthTag(tag);
      const plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
      this.highestSeenReceiveCounter = counter;
      return plaintext;
    } catch {
      // Authentication failed. Deliberately indistinguishable from any other rejection.
      return null;
    }
  }
}

function buildNonce(ivPrefix: Buffer, counter: bigint): Buffer {
  const nonce = Buffer.alloc(NONCE_BYTES);
  ivPrefix.copy(nonce, 0);
  nonce.writeBigUInt64BE(counter, IV_PREFIX_BYTES);
  return nonce;
}

function readCounter(nonce: Buffer): bigint {
  return nonce.readBigUInt64BE(IV_PREFIX_BYTES);
}

/** URL-safe unpadded base64, matching `PairingSecret.encode` on the device. */
export function decodePairingSecret(encoded: string): Buffer | null {
  try {
    const buffer = Buffer.from(encoded.trim(), "base64url");
    return buffer.length === SECRET_BYTES ? buffer : null;
  } catch {
    return null;
  }
}

export function encodePairingSecret(secret: Buffer): string {
  return secret.toString("base64url");
}

export function randomSalt(): Buffer {
  return randomBytes(SALT_BYTES);
}

/**
 * Parses the `droidpilot://host:port#secret` URI shown in the Android app.
 *
 * Accepting the whole URI matters for more than convenience: it keeps the host, port and
 * secret together as one value the user copies once, instead of three fields transcribed
 * separately with the secret most likely to be truncated.
 */
export function parsePairingUri(
  uri: string,
): { host: string; port: number; secret: Buffer } | null {
  const match = /^droidpilot:\/\/([^/:]+):(\d+)#(.+)$/.exec(uri.trim());
  if (!match) return null;

  const port = Number.parseInt(match[2], 10);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null;

  const secret = decodePairingSecret(match[3]);
  if (!secret) return null;

  return { host: match[1], port, secret };
}
