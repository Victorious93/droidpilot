package com.mobilemcp.pro.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestGuardTest {

    private var now = 1_000_000L
    private fun guard(skew: Long = 120_000, remember: Long = 600_000) =
        RequestGuard(maxSkewMillis = skew, rememberMillis = remember, clock = { now })

    private fun fresh(v: RequestGuard.Verdict) = v is RequestGuard.Verdict.Fresh

    @Test
    fun `a new request is admitted`() {
        assertTrue(fresh(guard().admit("req-1", now)))
    }

    @Test
    fun `the same id is refused the second time`() {
        val g = guard()
        assertTrue(fresh(g.admit("req-1", now)))
        assertTrue(g.admit("req-1", now) is RequestGuard.Verdict.Rejected)
    }

    @Test
    fun `distinct ids are independent`() {
        val g = guard()
        assertTrue(fresh(g.admit("req-1", now)))
        assertTrue(fresh(g.admit("req-2", now)))
    }

    @Test
    fun `a blank id is refused`() {
        assertTrue(guard().admit("", now) is RequestGuard.Verdict.Rejected)
    }

    @Test
    fun `a timestamp too far in the past is refused`() {
        assertTrue(guard().admit("req-1", now - 300_000) is RequestGuard.Verdict.Rejected)
    }

    /** Skew is checked in both directions: a future timestamp is equally suspect. */
    @Test
    fun `a timestamp too far in the future is refused`() {
        assertTrue(guard().admit("req-1", now + 300_000) is RequestGuard.Verdict.Rejected)
    }

    @Test
    fun `ordinary clock skew is tolerated`() {
        val g = guard()
        assertTrue(fresh(g.admit("req-1", now - 30_000)))
        assertTrue(fresh(g.admit("req-2", now + 30_000)))
    }

    /** A rejected request must not occupy a slot, or a typo could evict a real id. */
    @Test
    fun `a rejected request is not remembered`() {
        val g = guard()
        g.admit("req-1", now - 300_000)
        assertEquals(0, g.size())
    }

    @Test
    fun `ids are forgotten once they can no longer be replayed`() {
        val g = guard(skew = 60_000, remember = 120_000)
        g.admit("req-1", now)
        assertEquals(1, g.size())

        now += 200_000
        g.admit("req-2", now)

        assertEquals("the stale id should have been swept", 1, g.size())
    }

    /**
     * The retention window must outlast the skew window. If it did not, a replay delayed by
     * less than the tolerated skew could arrive after its id had been forgotten and be
     * admitted as new.
     */
    @Test
    fun `construction rejects a retention window shorter than the skew window`() {
        runCatching { RequestGuard(maxSkewMillis = 600_000, rememberMillis = 60_000) }
            .onSuccess { throw AssertionError("should have been rejected") }
            .onFailure { assertTrue(it is IllegalArgumentException) }
    }

    @Test
    fun `an id remains blocked for the whole retention window`() {
        val g = guard(skew = 60_000, remember = 600_000)
        g.admit("req-1", now)

        now += 300_000
        assertTrue("still inside the retention window", g.admit("req-1", now) is RequestGuard.Verdict.Rejected)
    }
}
