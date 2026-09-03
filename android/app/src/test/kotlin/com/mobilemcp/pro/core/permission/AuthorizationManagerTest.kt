package com.mobilemcp.pro.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security core. Every test here is a rule that must hold before this device will run
 * someone else's command as root, so the failure paths get more attention than the
 * successful ones.
 */
class AuthorizationManagerTest {

    private val laptop = "device-laptop"
    private val stranger = "device-stranger"

    private var now = 1_000_000L
    private val paired = mutableSetOf(laptop)

    private val registry = object : PairedDeviceRegistry {
        override fun isPaired(deviceId: String) = deviceId in paired
    }

    private val store = InMemoryGrantStore()
    private val manager = AuthorizationManager(store, registry, clock = { now })

    private fun allowed(decision: AuthorizationDecision) = decision is AuthorizationDecision.Allowed
    private fun denialOf(decision: AuthorizationDecision) = (decision as AuthorizationDecision.Denied).reason

    // ------------------------------------------------------------------ the basics

    @Test
    fun `a paired device with a live grant is allowed`() {
        manager.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_SHELL)))
    }

    /** Pairing establishes identity. It is not authorisation, and must never imply it. */
    @Test
    fun `pairing alone grants nothing`() {
        RemotePermission.entries.forEach { permission ->
            assertEquals(
                "pairing must not confer ${permission.wireName}",
                DenialReason.NO_GRANT,
                denialOf(manager.authorize(laptop, permission)),
            )
        }
    }

    @Test
    fun `an unpaired device is refused even holding a grant for its id`() {
        manager.grant(stranger, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        assertEquals(DenialReason.UNKNOWN_DEVICE, denialOf(manager.authorize(stranger, RemotePermission.REMOTE_ROOT)))
    }

    @Test
    fun `a grant for one permission does not authorise another`() {
        manager.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        assertEquals(DenialReason.NO_GRANT, denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    @Test
    fun `a grant for one device does not authorise another`() {
        paired += stranger
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        assertEquals(DenialReason.NO_GRANT, denialOf(manager.authorize(stranger, RemotePermission.REMOTE_ROOT)))
    }

    // -------------------------------------------------------------------- duration

    @Test
    fun `a single-use grant works exactly once`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Once)

        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
        assertEquals(DenialReason.ALREADY_USED, denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    @Test
    fun `a timed grant expires`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Until(now + 15 * 60_000))

        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
        now += 14 * 60_000
        assertTrue("still inside the window", allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))

        now += 2 * 60_000
        assertEquals(DenialReason.EXPIRED, denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    /** Expiry is evaluated at the instant of use, not when the grant was issued. */
    @Test
    fun `a timed grant is refused exactly at its expiry`() {
        val expiry = now + 60_000
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Until(expiry))

        now = expiry - 1
        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
        now = expiry
        assertEquals(DenialReason.EXPIRED, denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    @Test
    fun `an until-revoked grant survives the passage of time`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        now += 365L * 24 * 60 * 60 * 1000
        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    // ------------------------------------------------------------------ revocation

    /** Revocation must bite on the very next command, not when something later expires. */
    @Test
    fun `revocation takes effect immediately`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))

        manager.revoke(laptop, RemotePermission.REMOTE_ROOT)

        assertEquals(DenialReason.REVOKED, denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    @Test
    fun `revoking one permission leaves the others alone`() {
        manager.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        manager.revoke(laptop, RemotePermission.REMOTE_ROOT)

        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_SHELL)))
        assertFalse(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    @Test
    fun `revokeAll removes everything for one device only`() {
        paired += stranger
        manager.applyTrustLevel(laptop, TrustLevel.POWER_USER)
        manager.applyTrustLevel(stranger, TrustLevel.STANDARD)

        manager.revokeAll(laptop)

        assertTrue(manager.effectivePermissions(laptop).isEmpty())
        assertTrue(manager.effectivePermissions(stranger).isNotEmpty())
    }

    @Test
    fun `a revoked grant cannot be resurrected by time passing`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Until(now + 60_000))
        manager.revoke(laptop, RemotePermission.REMOTE_ROOT)

        now += 10_000
        assertFalse(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
    }

    // --------------------------------------------------------------------- AI_ROOT

    /**
     * The distinction the whole `AI_ROOT` permission exists for: the owner's laptop may run
     * root commands while the model may not.
     */
    @Test
    fun `AI cannot run root with REMOTE_ROOT alone`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        assertTrue(
            "a person at the paired device is authorised",
            allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.REMOTE_DEVICE)),
        )
        assertEquals(
            "the AI is not, without AI_ROOT",
            DenialReason.AI_ROOT_REQUIRED,
            denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.AI)),
        )
    }

    @Test
    fun `AI can run root when both permissions are granted`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        manager.grant(laptop, RemotePermission.AI_ROOT, GrantDuration.UntilRevoked)

        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.AI)))
    }

    /** AI_ROOT is a second gate, never a substitute for the first. */
    @Test
    fun `AI_ROOT alone does not authorise root`() {
        manager.grant(laptop, RemotePermission.AI_ROOT, GrantDuration.UntilRevoked)
        assertEquals(DenialReason.NO_GRANT, denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.AI)))
    }

    @Test
    fun `revoking AI_ROOT stops the AI while leaving the device authorised`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        manager.grant(laptop, RemotePermission.AI_ROOT, GrantDuration.UntilRevoked)

        manager.revoke(laptop, RemotePermission.AI_ROOT)

        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.REMOTE_DEVICE)))
        assertEquals(
            DenialReason.AI_ROOT_REQUIRED,
            denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.AI)),
        )
    }

    /**
     * A refused AI request must not burn the owner's single-use root grant — otherwise the
     * model could spend an authorisation it was never allowed to use.
     */
    @Test
    fun `an AI refusal does not consume a single-use root grant`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Once)

        assertFalse(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.AI)))

        assertTrue(
            "the owner's own single use must still be available",
            allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT, Initiator.REMOTE_DEVICE)),
        )
    }

    // ---------------------------------------------------------------- trust levels

    /** Root is never conferred by choosing a preset. It has its own explicit decision. */
    @Test
    fun `no trust level grants root`() {
        TrustLevel.entries.forEach { level ->
            assertFalse(
                "${level.name} must not include REMOTE_ROOT",
                RemotePermission.REMOTE_ROOT in level.includes,
            )
            assertFalse(
                "${level.name} must not include AI_ROOT",
                RemotePermission.AI_ROOT in level.includes,
            )
        }
    }

    @Test
    fun `applying the ROOT trust level still does not authorise root`() {
        manager.applyTrustLevel(laptop, TrustLevel.ROOT)

        assertEquals(DenialReason.NO_GRANT, denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
        assertTrue("but it does confer shell", allowed(manager.authorize(laptop, RemotePermission.REMOTE_SHELL)))
    }

    @Test
    fun `a preset expands into individual revocable grants`() {
        manager.applyTrustLevel(laptop, TrustLevel.STANDARD)
        assertEquals(TrustLevel.STANDARD.includes, manager.effectivePermissions(laptop))

        manager.revoke(laptop, RemotePermission.REMOTE_AUTOMATION)

        assertFalse(RemotePermission.REMOTE_AUTOMATION in manager.effectivePermissions(laptop))
        assertTrue(RemotePermission.REMOTE_VIEW in manager.effectivePermissions(laptop))
    }

    @Test
    fun `VIEW_ONLY confers nothing privileged`() {
        manager.applyTrustLevel(laptop, TrustLevel.VIEW_ONLY)
        assertTrue(manager.effectivePermissions(laptop).none { it.privileged })
    }

    // ------------------------------------------------------------------- behaviour

    /** A newer owner decision supersedes an older one rather than sitting alongside it. */
    @Test
    fun `re-granting replaces the previous grant`() {
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        manager.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Once)

        assertEquals(1, manager.activeGrants(laptop).size)
        assertTrue(allowed(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)))
        assertEquals(
            "the newer single-use grant is the one in force",
            DenialReason.ALREADY_USED,
            denialOf(manager.authorize(laptop, RemotePermission.REMOTE_ROOT)),
        )
    }

    @Test
    fun `effectivePermissions is empty for an unpaired device`() {
        manager.applyTrustLevel(laptop, TrustLevel.POWER_USER)
        paired -= laptop
        assertTrue(manager.effectivePermissions(laptop).isEmpty())
    }

    @Test
    fun `every decision is reported to the audit callback`() {
        val recorded = mutableListOf<Pair<RemotePermission, Boolean>>()
        val audited = AuthorizationManager(
            InMemoryGrantStore(), registry, clock = { now },
            onAudit = { decision, _, permission, _ ->
                recorded += permission to (decision is AuthorizationDecision.Allowed)
            },
        )

        audited.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        audited.authorize(laptop, RemotePermission.REMOTE_SHELL)
        audited.authorize(laptop, RemotePermission.REMOTE_ROOT)

        assertEquals(listOf(RemotePermission.REMOTE_SHELL to true, RemotePermission.REMOTE_ROOT to false), recorded)
    }
}
