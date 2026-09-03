package com.mobilemcp.pro

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilemcp.pro.security.PairingSecret
import com.mobilemcp.pro.security.PairingSecretStore
import com.mobilemcp.pro.security.SecureChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [PairingSecretStore] against the **real** Android Keystore.
 *
 * This cannot be done off-device. Robolectric reports `KeyStoreException: AndroidKeyStore
 * not found`, and there is no meaningful way to fake a keystore whose entire purpose is
 * that the key material never leaves the secure hardware — a fake would test the fake.
 *
 * The pairing secret is the sole credential protecting the device, so a silent failure
 * here is severe: if the wrapped blob could not be read back, the app would generate a
 * fresh secret on every launch and pairing would appear to work once and then stop.
 */
@RunWith(AndroidJUnit4::class)
class PairingSecretStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newStore() = PairingSecretStore(context)

    @Before
    fun clearPreviousState() {
        // Each test starts from a known secret rather than inheriting one from a prior run.
        newStore().regenerate()
    }

    @Test
    fun generatesASecretOfTheCorrectLength() {
        val secret = newStore().getOrCreate()
        assertEquals(SecureChannel.SECRET_BYTES, secret.size)
    }

    /**
     * The property that actually matters: a secret written by one instance must be
     * readable by another. This is the full wrap-with-Keystore-key / persist / reload /
     * unwrap round trip.
     */
    @Test
    fun theSecretSurvivesBeingReadBackByAFreshInstance() {
        val written = newStore().getOrCreate()
        val readBack = newStore().getOrCreate()

        assertArrayEquals(
            "a secret that cannot be read back would force re-pairing on every launch",
            written,
            readBack,
        )
    }

    @Test
    fun repeatedReadsAreStable() {
        val store = newStore()
        val first = store.getOrCreate()
        repeat(5) { assertArrayEquals(first, store.getOrCreate()) }
    }

    @Test
    fun regenerateProducesADifferentSecretAndPersistsIt() {
        val original = newStore().getOrCreate()
        val regenerated = newStore().regenerate()

        assertEquals(SecureChannel.SECRET_BYTES, regenerated.size)
        assertFalse("regenerate must not return the old secret", original.contentEquals(regenerated))
        assertArrayEquals("the new secret must be the one that persists", regenerated, newStore().getOrCreate())
    }

    @Test
    fun successiveSecretsDiffer() {
        val store = newStore()
        val secrets = (1..5).map { store.regenerate().toList() }
        assertEquals("every generated secret should be distinct", secrets.size, secrets.toSet().size)
    }

    /**
     * The stored form must not be the secret in the clear. This reads the SharedPreferences
     * file the store writes and asserts the plaintext does not appear in it — the concrete
     * form of "an attacker with a copy of the data directory gets ciphertext".
     */
    @Test
    fun theSecretIsNotStoredInPlaintext() {
        val secret = newStore().getOrCreate()

        val prefs = context.getSharedPreferences("droidpilot_secure", android.content.Context.MODE_PRIVATE)
        val stored = prefs.all.values.joinToString(" ") { it.toString() }

        assertTrue("expected a stored value", stored.isNotBlank())
        assertFalse(
            "the raw secret must never appear in stored form",
            stored.contains(PairingSecret.encode(secret)),
        )
        assertFalse(
            "the hex form of the secret must never appear either",
            stored.contains(secret.joinToString("") { "%02x".format(it) }),
        )
    }

    /** A secret from the store must work with the channel it exists to key. */
    @Test
    fun theStoredSecretKeysAWorkingSecureChannel() {
        val secret = newStore().getOrCreate()
        val salt = SecureChannel.randomSalt()

        val client = SecureChannel.derive(secret, salt, isServer = false)
        val server = SecureChannel.derive(secret, salt, isServer = true)

        val payload = """{"id":"1","command":"ping"}""".toByteArray()
        assertArrayEquals(payload, server.open(client.seal(payload)))
    }

    @Test
    fun fingerprintsTrackTheSecret() {
        val store = newStore()
        val first = PairingSecret.fingerprint(store.getOrCreate())
        assertEquals(first, PairingSecret.fingerprint(store.getOrCreate()))
        assertNotEquals(first, PairingSecret.fingerprint(store.regenerate()))
    }
}
