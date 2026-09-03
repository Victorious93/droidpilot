package com.mobilemcp.pro.core.permission

/**
 * Rejects replayed and stale privileged requests.
 *
 * The secure channel already refuses a replayed *record* by requiring strictly increasing
 * counters, which stops an attacker re-sending captured bytes on the same session. This
 * guards the layer above: a request id that has already been executed must not run again,
 * even if it arrives on a fresh, legitimate session — and a request whose timestamp is far
 * from now must not run at all.
 *
 * Both matter specifically because these requests are root commands. Re-running an
 * arbitrary command is not an idempotent retry; it is a second real execution.
 */
class RequestGuard(
    /** How far a request's timestamp may sit from local time. Covers ordinary clock skew. */
    private val maxSkewMillis: Long = DEFAULT_MAX_SKEW_MILLIS,
    /** How long an executed id is remembered. Must exceed [maxSkewMillis] to be sound. */
    private val rememberMillis: Long = DEFAULT_REMEMBER_MILLIS,
    /**
     * Hard ceiling on remembered ids.
     *
     * Without one, the set grows with every distinct id inside the window, and an
     * authenticated peer can enlarge it without limit simply by sending requests. The
     * ceiling is reached by refusing new requests rather than by evicting ids that are
     * still inside their window: dropping an id early would silently stop protecting
     * against the replay of exactly that request, which is the one thing this class exists
     * to prevent. Refusing is visible and recoverable; forgetting is neither.
     */
    private val maxRemembered: Int = DEFAULT_MAX_REMEMBERED,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(rememberMillis > maxSkewMillis) {
            "Ids must be remembered for longer than a request can legitimately be delayed, " +
                "or a replay could arrive after its id has been forgotten"
        }
    }

    sealed interface Verdict {
        data object Fresh : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    private val seen = LinkedHashMap<String, Long>()

    /**
     * Records [requestId] as executed and reports whether it was new.
     *
     * Admission and recording are one atomic step on purpose: checking first and recording
     * afterwards leaves a window in which two concurrent copies of the same request both
     * see it as fresh, which is exactly the case this exists to prevent.
     */
    @Synchronized
    fun admit(requestId: String, requestTimestampMillis: Long): Verdict {
        val now = clock()
        evictExpired(now)

        if (requestId.isBlank()) {
            return Verdict.Rejected("Request has no id")
        }

        val skew = kotlin.math.abs(now - requestTimestampMillis)
        if (skew > maxSkewMillis) {
            return Verdict.Rejected(
                "Request timestamp is ${skew / 1000}s from this device's clock, beyond the " +
                    "${maxSkewMillis / 1000}s tolerance. Check that both clocks are correct.",
            )
        }

        if (seen.containsKey(requestId)) {
            return Verdict.Rejected("Request '$requestId' has already been executed")
        }

        if (seen.size >= maxRemembered) {
            return Verdict.Rejected(
                "Too many privileged requests in the last ${rememberMillis / 1000}s to " +
                    "guarantee replay protection; refusing rather than forgetting one. " +
                    "Retry shortly.",
            )
        }

        seen[requestId] = now
        return Verdict.Fresh
    }

    private fun evictExpired(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            // Insertion-ordered, so the first entry still within the window ends the sweep.
            if (now - iterator.next().value <= rememberMillis) break
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() = seen.clear()

    @Synchronized
    fun size(): Int = seen.size

    private companion object {
        const val DEFAULT_MAX_SKEW_MILLIS = 120_000L
        const val DEFAULT_REMEMBER_MILLIS = 600_000L

        /** Far above any legitimate rate; low enough that the set cannot exhaust memory. */
        const val DEFAULT_MAX_REMEMBERED = 20_000
    }
}
