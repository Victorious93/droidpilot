package com.mobilemcp.pro.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecretTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `encoding round-trips`() {
        assertArrayEquals(secret, PairingSecret.decode(PairingSecret.encode(secret)))
    }

    @Test
    fun `encoding is url-safe and unpadded`() {
        val encoded = PairingSecret.encode(secret)
        assertEquals(43, encoded.length)
        assertFalse("must not contain padding", encoded.contains('='))
        assertFalse("must not contain '+'", encoded.contains('+'))
        assertFalse("must not contain '/'", encoded.contains('/'))
    }

    @Test
    fun `surrounding whitespace is tolerated when decoding`() {
        // Users paste this out of a terminal or a chat message; a stray newline should not
        // read as a wrong secret.
        val encoded = PairingSecret.encode(secret)
        assertArrayEquals(secret, PairingSecret.decode("  $encoded\n"))
    }

    @Test
    fun `malformed input decodes to null rather than throwing`() {
        listOf("", "!!!", "abc", "a".repeat(100), "====")
            .forEach { assertNull("'$it' should not decode", PairingSecret.decode(it)) }
    }

    @Test
    fun `values of the wrong length are rejected`() {
        val shortSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val longSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))

        assertNull(PairingSecret.decode(shortSecret))
        assertNull(PairingSecret.decode(longSecret))
    }

    @Test
    fun `matches compares by value`() {
        assertTrue(PairingSecret.matches(secret, secret.copyOf()))
        assertFalse(PairingSecret.matches(secret, ByteArray(32) { (it + 1).toByte() }))
        assertFalse(PairingSecret.matches(secret, ByteArray(31)))
    }

    /** A single differing bit must fail — including one in the very last byte. */
    @Test
    fun `matches rejects a one-bit difference at the end`() {
        val almost = secret.copyOf().also { it[31] = (it[31].toInt() xor 0x01).toByte() }
        assertFalse(PairingSecret.matches(secret, almost))
    }

    @Test
    fun `fingerprints are stable and differ between secrets`() {
        val fingerprint = PairingSecret.fingerprint(secret)
        assertEquals(8, fingerprint.length)
        assertEquals(fingerprint, PairingSecret.fingerprint(secret.copyOf()))
        assertNotEquals(fingerprint, PairingSecret.fingerprint(ByteArray(32) { (it + 1).toByte() }))
    }

    /**
     * The fingerprint is displayed in the UI and safe to include in a bug report, so it
     * must not be the secret in disguise.
     */
    @Test
    fun `the fingerprint does not contain the secret`() {
        assertFalse(PairingSecret.fingerprint(secret).contains(PairingSecret.encode(secret)))
    }

    @Test
    fun `pairing uri carries host port and secret`() {
        val uri = PairingSecret.pairingUri("192.168.1.42", 8765, secret)
        assertEquals("droidpilot://192.168.1.42:8765#${PairingSecret.encode(secret)}", uri)
    }
}
