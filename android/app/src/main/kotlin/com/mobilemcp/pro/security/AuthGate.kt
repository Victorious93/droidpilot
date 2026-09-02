package com.mobilemcp.pro.security

/**
 * Decides whether a connecting client may proceed, and throttles clients that keep
 * guessing.
 *
 * A 256-bit secret is not realistically brute-forceable, so the lockout here is not the
 * primary defence — it is a backstop against the operator having configured a weak or
 * leaked secret, and it makes an ongoing attack visible in the log rather than silent.
 *
 * Deliberately free of Android and networking types: this is decision logic, and it is
 * covered directly by unit tests. The caller supplies time, so tests need no sleeping.
 */
class AuthGate(
    private val maxFailuresPerWindow: Int = DEFAULT_MAX_FAILURES,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val lockoutMillis: Long = DEFAULT_LOCKOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Decision {
        data object Allowed : Decision
        data class Rejected(val reason: String) : Decision
        data class LockedOut(val retryAfterMillis: Long) : Decision
    }

    private class PeerState {
        var failures: Int = 0
        var windowStartedAt: Long = 0
        var lockedUntil: Long = 0
    }

    private val peers = HashMap<String, PeerState>()

    /**
     * Evaluates a connection attempt from [peerId] presenting [presented].
     *
     * [peerId] should identify the remote host (not the ephemeral port), so that an
     * attacker cannot reset their own budget by opening a new socket.
     */
    @Synchronized
    fun evaluate(peerId: String, expectedSecret: ByteArray, presented: String?): Decision {
        val now = clock()
        val state = peers.getOrPut(peerId) { PeerState() }

        if (now < state.lockedUntil) {
            return Decision.LockedOut(state.lockedUntil - now)
        }

        if (now - state.windowStartedAt > windowMillis) {
            state.failures = 0
            state.windowStartedAt = now
        }

        val candidate = presented?.let(PairingSecret::decode)
        if (candidate != null && PairingSecret.matches(expectedSecret, candidate)) {
            peers.remove(peerId)
            return Decision.Allowed
        }

        state.failures++
        if (state.failures >= maxFailuresPerWindow) {
            state.lockedUntil = now + lockoutMillis
            state.failures = 0
            state.windowStartedAt = now
            return Decision.LockedOut(lockoutMillis)
        }

        // The message never distinguishes "missing" from "wrong": telling an attacker
        // which half of the credential they got right is free information.
        return Decision.Rejected("Invalid or missing pairing secret")
    }

    /** Drops accumulated state, e.g. after the operator regenerates the secret. */
    @Synchronized
    fun reset() = peers.clear()

    companion object {
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val DEFAULT_LOCKOUT_MILLIS = 300_000L
    }
}
