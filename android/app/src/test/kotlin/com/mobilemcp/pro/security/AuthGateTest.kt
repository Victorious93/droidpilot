package com.mobilemcp.pro.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGateTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val encoded = PairingSecret.encode(secret)

    private var now = 1_000_000L
    private fun gate(maxFailures: Int = 5, window: Long = 60_000, lockout: Long = 300_000) =
        AuthGate(maxFailures, window, lockout) { now }

    @Test
    fun `the correct secret is allowed`() {
        assertEquals(AuthGate.Decision.Allowed, gate().evaluate("10.0.0.5", secret, encoded))
    }

    @Test
    fun `a wrong secret is rejected`() {
        val wrong = PairingSecret.encode(ByteArray(32) { (it + 1).toByte() })
        assertTrue(gate().evaluate("10.0.0.5", secret, wrong) is AuthGate.Decision.Rejected)
    }

    @Test
    fun `a missing secret is rejected`() {
        assertTrue(gate().evaluate("10.0.0.5", secret, null) is AuthGate.Decision.Rejected)
    }

    @Test
    fun `malformed secrets are rejected rather than throwing`() {
        val gate = gate(maxFailures = 100)
        listOf("", "not-base64!!", "short", "=".repeat(43), "a".repeat(1000))
            .forEach { candidate ->
                assertTrue(
                    "'$candidate' should be rejected",
                    gate.evaluate("10.0.0.5", secret, candidate) is AuthGate.Decision.Rejected,
                )
            }
    }

    /**
     * A secret of the right length but the wrong value must be rejected — a decode that
     * succeeds is not an authentication that succeeds.
     */
    @Test
    fun `a well-formed but incorrect secret is rejected`() {
        val other = PairingSecret.encode(ByteArray(32) { 0x7F })
        assertTrue(gate().evaluate("10.0.0.5", secret, other) is AuthGate.Decision.Rejected)
    }

    @Test
    fun `repeated failures trigger a lockout`() {
        val gate = gate(maxFailures = 3)
        repeat(2) { assertTrue(gate.evaluate("10.0.0.5", secret, "wrong") is AuthGate.Decision.Rejected) }

        val decision = gate.evaluate("10.0.0.5", secret, "wrong")
        assertTrue(decision is AuthGate.Decision.LockedOut)
        assertEquals(300_000L, (decision as AuthGate.Decision.LockedOut).retryAfterMillis)
    }

    /**
     * The lockout must survive a correct guess. Otherwise an attacker who finally hits the
     * right value during a lockout walks straight in, and the lockout has bought nothing.
     */
    @Test
    fun `a locked out peer is refused even with the correct secret`() {
        val gate = gate(maxFailures = 2)
        repeat(2) { gate.evaluate("10.0.0.5", secret, "wrong") }

        assertTrue(gate.evaluate("10.0.0.5", secret, encoded) is AuthGate.Decision.LockedOut)
    }

    @Test
    fun `the lockout expires`() {
        val gate = gate(maxFailures = 2, lockout = 300_000)
        repeat(2) { gate.evaluate("10.0.0.5", secret, "wrong") }
        assertTrue(gate.evaluate("10.0.0.5", secret, encoded) is AuthGate.Decision.LockedOut)

        now += 300_001
        assertEquals(AuthGate.Decision.Allowed, gate.evaluate("10.0.0.5", secret, encoded))
    }

    @Test
    fun `failures outside the window do not accumulate`() {
        val gate = gate(maxFailures = 3, window = 60_000)

        repeat(2) { gate.evaluate("10.0.0.5", secret, "wrong") }
        now += 60_001
        // The window has rolled, so this is failure 1 of 3 again, not 3 of 3.
        assertTrue(gate.evaluate("10.0.0.5", secret, "wrong") is AuthGate.Decision.Rejected)
    }

    /**
     * Budgets are per peer host. If they were shared, one misconfigured client could lock
     * the legitimate one out — a denial of service against the user's own device.
     */
    @Test
    fun `lockout applies per peer`() {
        val gate = gate(maxFailures = 2)
        repeat(2) { gate.evaluate("10.0.0.5", secret, "wrong") }

        assertTrue(gate.evaluate("10.0.0.5", secret, encoded) is AuthGate.Decision.LockedOut)
        assertEquals(AuthGate.Decision.Allowed, gate.evaluate("10.0.0.9", secret, encoded))
    }

    @Test
    fun `a successful authentication clears accumulated failures`() {
        val gate = gate(maxFailures = 3)
        repeat(2) { gate.evaluate("10.0.0.5", secret, "wrong") }

        assertEquals(AuthGate.Decision.Allowed, gate.evaluate("10.0.0.5", secret, encoded))

        // Budget reset: two more failures must not immediately lock out.
        repeat(2) {
            assertTrue(gate.evaluate("10.0.0.5", secret, "wrong") is AuthGate.Decision.Rejected)
        }
    }

    @Test
    fun `reset clears all peer state`() {
        val gate = gate(maxFailures = 2)
        repeat(2) { gate.evaluate("10.0.0.5", secret, "wrong") }
        gate.reset()

        assertEquals(AuthGate.Decision.Allowed, gate.evaluate("10.0.0.5", secret, encoded))
    }

    /**
     * The rejection message must not reveal whether the secret was absent or merely wrong;
     * that distinction is free information for an attacker.
     */
    @Test
    fun `rejection messages do not distinguish missing from incorrect`() {
        val gate = gate(maxFailures = 100)
        val missing = gate.evaluate("10.0.0.5", secret, null) as AuthGate.Decision.Rejected
        val incorrect = gate.evaluate("10.0.0.6", secret, "wrong") as AuthGate.Decision.Rejected

        assertEquals(missing.reason, incorrect.reason)
    }
}
