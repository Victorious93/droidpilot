package com.mobilemcp.pro.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authorisation state has to outlive the process, and it has to fail closed when it cannot
 * be read. Both are tested here against an in-memory backing store, so the decision logic is
 * exercised with no Android runtime and no test-time downloads — the storage lambdas exist
 * precisely to make that possible.
 */
class PersistentGrantStoreTest {

    /** Stands in for the file, so a "restart" is just a second store over the same string. */
    private class Backing(var raw: String? = null) {
        var writes = 0
        fun read(): String? = raw
        fun write(value: String) {
            raw = value
            writes++
        }
    }

    private val backing = Backing()
    private fun newStore() = PersistentGrantStore(backing::read, backing::write)

    private fun grant(
        id: String = "g1",
        device: String = "device-a",
        permission: RemotePermission = RemotePermission.REMOTE_ROOT,
        duration: GrantDuration = GrantDuration.UntilRevoked,
    ) = Grant(
        id = id,
        deviceId = device,
        permission = permission,
        duration = duration,
        grantedAtMillis = 1_000L,
    )

    // ------------------------------------------------------------------ durability

    @Test
    fun `a grant survives a restart`() {
        newStore().put(grant())

        val reloaded = newStore().grantsFor("device-a")

        assertEquals(1, reloaded.size)
        assertEquals(RemotePermission.REMOTE_ROOT, reloaded.single().permission)
    }

    /**
     * The direction that matters more. A grant lost on restart is an inconvenience; a
     * *revocation* lost on restart re-authorises a device the owner deliberately cut off.
     */
    @Test
    fun `a revocation survives a restart`() {
        val store = newStore()
        store.put(grant())
        store.replace(store.grantsFor("device-a").single().copy(revokedAtMillis = 2_000L))

        val reloaded = newStore().grantsFor("device-a").single()

        assertTrue("a revoked grant must not come back alive", reloaded.isRevoked)
        assertEquals(false, reloaded.isActiveAt(3_000L))
    }

    @Test
    fun `consumption of a single-use grant survives a restart`() {
        val store = newStore()
        store.put(grant(duration = GrantDuration.Once))
        store.replace(store.grantsFor("device-a").single().copy(consumedAtMillis = 2_000L))

        assertTrue(newStore().grantsFor("device-a").single().isConsumed)
    }

    @Test
    fun `every duration shape round-trips`() {
        val store = newStore()
        store.put(grant(id = "once", duration = GrantDuration.Once))
        store.put(grant(id = "timed", duration = GrantDuration.Until(9_999L)))
        store.put(grant(id = "open", duration = GrantDuration.UntilRevoked))

        val reloaded = newStore().all().associateBy { it.id }

        assertEquals(GrantDuration.Once, reloaded.getValue("once").duration)
        assertEquals(GrantDuration.Until(9_999L), reloaded.getValue("timed").duration)
        assertEquals(GrantDuration.UntilRevoked, reloaded.getValue("open").duration)
    }

    @Test
    fun `grants are separated by device`() {
        val store = newStore()
        store.put(grant(id = "a", device = "device-a"))
        store.put(grant(id = "b", device = "device-b"))

        assertEquals(listOf("a"), store.grantsFor("device-a").map { it.id })
        assertEquals(listOf("b"), store.grantsFor("device-b").map { it.id })
    }

    // ---------------------------------------------------------------- failing shut

    @Test
    fun `unreadable stored data is treated as no grants`() {
        backing.raw = "{ this is not the json we wrote"

        assertTrue(
            "corrupt authorisation data must never be read optimistically",
            newStore().all().isEmpty(),
        )
    }

    @Test
    fun `truncated stored data is treated as no grants`() {
        newStore().put(grant())
        backing.raw = backing.raw!!.take(backing.raw!!.length / 2)

        assertTrue(newStore().all().isEmpty())
    }

    @Test
    fun `an absent store starts empty rather than failing`() {
        backing.raw = null
        assertTrue(newStore().all().isEmpty())
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun `forgetting all but the current device drops the rest`() {
        val store = newStore()
        store.put(grant(id = "old", device = "old-device"))
        store.put(grant(id = "new", device = "new-device"))

        store.forgetAllExcept("new-device")

        assertEquals(listOf("new"), newStore().all().map { it.id })
    }

    @Test
    fun `forgetting with no current device empties the store`() {
        val store = newStore()
        store.put(grant(id = "old", device = "old-device"))

        store.forgetAllExcept(null)

        assertTrue(newStore().all().isEmpty())
    }

    @Test
    fun `forgetting nothing does not rewrite the file`() {
        val store = newStore()
        store.put(grant(device = "device-a"))
        val writesBefore = backing.writes

        store.forgetAllExcept("device-a")

        assertEquals("a no-op must not touch storage", writesBefore, backing.writes)
    }

    @Test
    fun `clearing removes everything`() {
        val store = newStore()
        store.put(grant())
        store.clear()

        assertTrue(newStore().all().isEmpty())
    }

    // -------------------------------------------------------------------- secrecy

    /**
     * The device id is a hash of the pairing secret, and only the hash may be written. If a
     * refactor ever started storing the secret itself, this is the test that should stop it.
     */
    @Test
    fun `no secret material is written`() {
        newStore().put(grant(device = "a1b2c3d4e5f6"))

        val written = backing.raw!!
        assertNotNull(written)
        assertTrue("the device id is expected", written.contains("a1b2c3d4e5f6"))
        assertTrue(
            "nothing resembling a base64 secret should be present",
            Regex("[A-Za-z0-9+/]{40,}={0,2}").findAll(written).none(),
        )
    }

    @Test
    fun `an id written twice replaces rather than duplicates`() {
        val store = newStore()
        store.put(grant(id = "same"))
        store.put(grant(id = "same", permission = RemotePermission.REMOTE_SHELL))

        val all = newStore().all()
        assertEquals(1, all.size)
        assertEquals(RemotePermission.REMOTE_SHELL, all.single().permission)
    }

    @Test
    fun `an empty store writes valid, reloadable data`() {
        newStore().clear()

        assertNull(newStore().grantsFor("anything").firstOrNull())
        assertTrue(newStore().all().isEmpty())
    }
}
