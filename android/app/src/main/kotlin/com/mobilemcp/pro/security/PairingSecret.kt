package com.mobilemcp.pro.security

import java.security.MessageDigest
import java.util.Base64

/**
 * Encoding and comparison helpers for the 32-byte pairing secret.
 *
 * The secret is the sole authority on the control channel, so everything that touches it
 * is centralised here: there is exactly one encoder, one decoder and one comparison, and
 * the comparison is constant-time.
 *
 * `java.util.Base64` is used rather than `android.util.Base64` so that this class carries
 * no Android dependency and can be covered by ordinary JVM unit tests.
 */
object PairingSecret {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** URL-safe, unpadded base64 — 43 characters for a 32-byte secret. */
    fun encode(secret: ByteArray): String = encoder.encodeToString(secret)

    /** Returns `null` for anything that is not a well-formed secret of the right length. */
    fun decode(encoded: String): ByteArray? = try {
        decoder.decode(encoded.trim()).takeIf { it.size == SecureChannel.SECRET_BYTES }
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Constant-time equality.
     *
     * `MessageDigest.isEqual` is specified to compare without short-circuiting, which
     * matters because a naive `==` on the secret would leak its prefix to an attacker who
     * can time responses — and on a LAN an attacker can time responses precisely.
     */
    fun matches(expected: ByteArray, candidate: ByteArray): Boolean =
        MessageDigest.isEqual(expected, candidate)

    /**
     * A short, non-secret fingerprint of the secret, for confirming that phone and client
     * were paired with the same value without displaying the secret itself. Shown in the
     * app UI and logged; safe to put in a screenshot or a bug report.
     */
    fun fingerprint(secret: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(secret)
            .take(4)
            .joinToString("") { "%02X".format(it) }

    /**
     * One-line pairing string the user copies from the phone into their MCP client
     * configuration: `droidpilot://<host>:<port>#<secret>`.
     *
     * The secret sits in the fragment because fragments are conventionally not written to
     * server logs or sent upstream by URL-handling code — a small defence for the case
     * where someone pastes this into the wrong box.
     */
    fun pairingUri(host: String, port: Int, secret: ByteArray): String =
        "droidpilot://$host:$port#${encode(secret)}"
}
