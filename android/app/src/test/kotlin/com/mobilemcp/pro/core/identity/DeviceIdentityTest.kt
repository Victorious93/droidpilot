package com.mobilemcp.pro.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity is derived from the pairing secret, which gives the system one property that
 * would otherwise need code to enforce: regenerating the secret voids every grant. These
 * tests pin that, because it is the behaviour an owner will reach for when something has
 * gone wrong and they want everything revoked at once.
 */
class DeviceIdentityTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val otherSecret = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `the same secret always yields the same id`() {
        assertEquals(DeviceIdentity.forSecret(secret), DeviceIdentity.forSecret(secret.copyOf()))
    }

    @Test
    fun `a different secret yields a different id`() {
        assertNotEquals(DeviceIdentity.forSecret(secret), DeviceIdentity.forSecret(otherSecret))
    }

    @Test
    fun `the id is a full-length hash, not a display fingerprint`() {
        val id = DeviceIdentity.forSecret(secret)
        assertEquals("SHA-256 as hex", 64, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    /**
     * The id must not be the secret, nor recoverable from a substring of it. This is a
     * shallow check — the real guarantee is that it is a one-way hash — but it catches the
     * category of mistake where a refactor starts passing the secret itself around as an
     * identifier.
     */
    @Test
    fun `the id does not contain the secret`() {
        val id = DeviceIdentity.forSecret(secret)
        val secretHex = secret.joinToString("") { "%02x".format(it) }
        assertFalse(id.contains(secretHex))
    }

    @Test
    fun `the short form is a prefix of the id`() {
        val id = DeviceIdentity.forSecret(secret)
        assertTrue(id.startsWith(DeviceIdentity.shortForm(id)))
    }

    // ------------------------------------------------------------------- registry

    @Test
    fun `only the holder of the current secret is paired`() {
        val registry = SecretBoundPairedDeviceRegistry { secret }

        assertTrue(registry.isPaired(DeviceIdentity.forSecret(secret)))
        assertFalse(registry.isPaired(DeviceIdentity.forSecret(otherSecret)))
    }

    @Test
    fun `nothing is paired before a secret exists`() {
        val registry = SecretBoundPairedDeviceRegistry { null }

        assertNull(registry.currentDeviceId())
        assertFalse(registry.isPaired(DeviceIdentity.forSecret(secret)))
        assertFalse("an absent secret must not pair the empty id either", registry.isPaired(""))
    }

    @Test
    fun `a blank id is never paired`() {
        val registry = SecretBoundPairedDeviceRegistry { secret }

        assertFalse(registry.isPaired(""))
        assertFalse(registry.isPaired("   "))
    }

    /**
     * The property the design exists for: rotating the secret unpairs the previous holder
     * immediately, without anything having to remember to revoke.
     */
    @Test
    fun `regenerating the secret unpairs the previous device`() {
        var current = secret
        val registry = SecretBoundPairedDeviceRegistry { current }
        val idBefore = registry.currentDeviceId()!!

        assertTrue(registry.isPaired(idBefore))

        current = otherSecret

        assertFalse("the old identity must stop being paired at once", registry.isPaired(idBefore))
        assertNotEquals(idBefore, registry.currentDeviceId())
    }

    /** The secret is read per call, so a rotation is not masked by a captured value. */
    @Test
    fun `the secret is read on every check rather than captured`() {
        var reads = 0
        val registry = SecretBoundPairedDeviceRegistry { reads++; secret }

        registry.isPaired("x")
        registry.isPaired("y")
        registry.currentDeviceId()

        assertEquals(3, reads)
    }
}
