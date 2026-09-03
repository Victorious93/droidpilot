package com.mobilemcp.pro.security

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Application-layer authenticated encryption for the DroidPilot control channel.
 *
 * ## Why this and not TLS
 *
 * The channel connects two devices the same person owns, already paired out-of-band by
 * copying a secret from the phone's screen. That is a pre-shared-key problem, not a PKI
 * problem: there is no third party to vouch for, no name to verify, and no revocation
 * story. Self-signed TLS would add certificate generation, a trust-on-first-use flow and
 * a fingerprint the user has to compare — all to re-derive a shared secret the user
 * already transferred by hand.
 *
 * Equally decisive in practice: this construction is exercised by ordinary JVM unit tests
 * and by cross-implementation vectors shared with the Node client, whereas an
 * `AndroidKeyStore`-backed TLS stack can only be tested on a physical device.
 *
 * ## Construction
 *
 * Standard primitives, no novel cryptography:
 *
 *  - **Key schedule** — HKDF-SHA256 (RFC 5869). Extract over the 32-byte pairing secret
 *    with a 16-byte server-chosen per-session salt, then a single 72-byte expand split
 *    into `c2s key ‖ c2s iv ‖ s2c key ‖ s2c iv`. A fresh salt per connection means every
 *    session uses distinct keys even though the pairing secret is long-lived.
 *  - **Record protection** — AES-256-GCM. The 96-bit nonce is a 4-byte direction-specific
 *    prefix from the key schedule followed by a 8-byte big-endian record counter, the
 *    layout TLS 1.2 AEAD suites use. Counters never repeat within a session and keys never
 *    repeat across sessions, so a nonce is never reused under a key.
 *  - **Replay and reorder** — the receiver requires strictly increasing counters, so a
 *    replayed or reordered record is rejected rather than re-executed. This matters here:
 *    records are device commands, and a replayed `tap` is a real action.
 *  - **Direction separation** — client-to-server and server-to-client use different keys,
 *    so a record cannot be reflected back at its sender.
 *
 * Records are carried as binary WebSocket frames laid out as `nonce(12) ‖ ciphertext ‖ tag(16)`.
 *
 * ## What this does not defend against
 *
 * Anyone holding the pairing secret is indistinguishable from the legitimate client — the
 * secret is the entire authority. There is no forward secrecy: an attacker who records
 * traffic and later learns the secret can decrypt those recordings. Rotating the secret
 * (Settings → Regenerate) is what bounds that exposure. See SECURITY.md.
 */
class SecureChannel private constructor(
    private val sendKey: SecretKeySpec,
    private val sendIvPrefix: ByteArray,
    private val receiveKey: SecretKeySpec,
    private val receiveIvPrefix: ByteArray,
) {

    private var sendCounter: Long = 0
    private var highestSeenReceiveCounter: Long = -1

    /**
     * Encrypts [plaintext] into a self-contained record.
     *
     * Synchronised, so two callers can never be handed the same counter and therefore
     * never reuse a nonce under the same key. Note that this makes *numbering* atomic and
     * nothing more: a caller that transmits records concurrently must also serialise the
     * seal with the write, or records can reach the peer out of counter order and be
     * rejected as replays. `ControlServer.sendEncrypted` does exactly that.
     */
    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val counter = sendCounter
        require(counter != Long.MAX_VALUE) { "Record counter exhausted; reconnect required" }
        sendCounter = counter + 1

        val nonce = buildNonce(sendIvPrefix, counter)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, nonce))
        }
        val sealed = cipher.doFinal(plaintext)

        return ByteArray(NONCE_BYTES + sealed.size).also {
            nonce.copyInto(it, 0)
            sealed.copyInto(it, NONCE_BYTES)
        }
    }

    /**
     * Verifies and decrypts a record produced by the peer.
     *
     * Returns `null` for any record that fails authentication, is truncated, uses a
     * mismatched IV prefix, or replays a counter already seen. Callers must treat `null`
     * as hostile input and drop the connection rather than retrying — a failure here means
     * either corruption or an active attacker, and neither is worth a second attempt.
     */
    @Synchronized
    fun open(record: ByteArray): ByteArray? {
        if (record.size < NONCE_BYTES + TAG_BYTES) return null

        val nonce = record.copyOfRange(0, NONCE_BYTES)
        if (!MessageDigest.isEqual(nonce.copyOfRange(0, IV_PREFIX_BYTES), receiveIvPrefix)) {
            return null
        }

        val counter = readCounter(nonce)
        if (counter <= highestSeenReceiveCounter) return null

        val decrypted = try {
            Cipher.getInstance(AES_GCM_TRANSFORM).run {
                init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(TAG_BITS, nonce))
                doFinal(record, NONCE_BYTES, record.size - NONCE_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException and friends: authentication failed. Deliberately opaque —
            // distinguishing failure modes here would leak information to an attacker.
            return null
        }

        highestSeenReceiveCounter = counter
        return decrypted
    }

    companion object {
        const val SECRET_BYTES = 32
        const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val IV_PREFIX_BYTES = 4
        private const val COUNTER_BYTES = 8
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val KEY_BYTES = 32
        private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
        private const val HMAC_SHA256 = "HmacSHA256"

        /** Domain-separation label. Changing this is a breaking protocol change. */
        private const val HKDF_INFO = "droidpilot/v2/keys"

        private val random = SecureRandom()

        fun randomSecret(): ByteArray = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }

        fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

        /**
         * Derives a channel from the pairing [secret] and a per-session [salt].
         *
         * [isServer] selects which half of the key schedule is used for sending, so the
         * two peers derive mirrored views of the same material.
         */
        fun derive(secret: ByteArray, salt: ByteArray, isServer: Boolean): SecureChannel {
            require(secret.size == SECRET_BYTES) { "Pairing secret must be $SECRET_BYTES bytes" }
            require(salt.size == SALT_BYTES) { "Session salt must be $SALT_BYTES bytes" }

            val material = hkdf(secret, salt, HKDF_INFO.toByteArray(), 2 * (KEY_BYTES + IV_PREFIX_BYTES))
            var offset = 0
            fun take(n: Int): ByteArray = material.copyOfRange(offset, offset + n).also { offset += n }

            val c2sKey = take(KEY_BYTES)
            val c2sIv = take(IV_PREFIX_BYTES)
            val s2cKey = take(KEY_BYTES)
            val s2cIv = take(IV_PREFIX_BYTES)

            return if (isServer) {
                SecureChannel(
                    sendKey = SecretKeySpec(s2cKey, "AES"),
                    sendIvPrefix = s2cIv,
                    receiveKey = SecretKeySpec(c2sKey, "AES"),
                    receiveIvPrefix = c2sIv,
                )
            } else {
                SecureChannel(
                    sendKey = SecretKeySpec(c2sKey, "AES"),
                    sendIvPrefix = c2sIv,
                    receiveKey = SecretKeySpec(s2cKey, "AES"),
                    receiveIvPrefix = s2cIv,
                )
            }
        }

        /** HKDF-SHA256 (RFC 5869), extract-then-expand. */
        internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance(HMAC_SHA256)
            val hashLen = mac.macLength
            require(length <= 255 * hashLen) { "HKDF output too long" }

            mac.init(SecretKeySpec(salt, HMAC_SHA256))
            val prk = mac.doFinal(ikm)

            val output = ByteArray(length)
            var previousBlock = ByteArray(0)
            var produced = 0
            var counter = 1

            while (produced < length) {
                mac.init(SecretKeySpec(prk, HMAC_SHA256))
                mac.update(previousBlock)
                mac.update(info)
                mac.update(counter.toByte())
                previousBlock = mac.doFinal()

                val chunk = minOf(hashLen, length - produced)
                previousBlock.copyInto(output, produced, 0, chunk)
                produced += chunk
                counter++
            }
            return output
        }

        private fun buildNonce(ivPrefix: ByteArray, counter: Long): ByteArray =
            ByteArray(NONCE_BYTES).also { nonce ->
                ivPrefix.copyInto(nonce, 0)
                for (i in 0 until COUNTER_BYTES) {
                    nonce[IV_PREFIX_BYTES + i] =
                        ((counter shr (8 * (COUNTER_BYTES - 1 - i))) and 0xFF).toByte()
                }
            }

        private fun readCounter(nonce: ByteArray): Long {
            var value = 0L
            for (i in 0 until COUNTER_BYTES) {
                value = (value shl 8) or (nonce[IV_PREFIX_BYTES + i].toLong() and 0xFF)
            }
            return value
        }
    }
}
