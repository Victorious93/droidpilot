package com.mobilemcp.pro.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTrackerTest {

    private fun step(id: String, status: ActionStatus = ActionStatus.SUCCESS) = ExecutionStep(
        requestId = id,
        command = "tap",
        status = status,
        summary = "Completed",
        startedAtMillis = 0L,
        finishedAtMillis = 1L,
    )

    @Test
    fun `starts empty`() {
        assertTrue(ExecutionTracker().steps.value.isEmpty())
    }

    @Test
    fun `record appends in order`() {
        val tracker = ExecutionTracker()

        tracker.record(step("1"))
        tracker.record(step("2"))

        assertEquals(listOf("1", "2"), tracker.steps.value.map { it.requestId })
    }

    @Test
    fun `caps at maxEntries, dropping the oldest`() {
        val tracker = ExecutionTracker(maxEntries = 2)

        tracker.record(step("1"))
        tracker.record(step("2"))
        tracker.record(step("3"))

        assertEquals(listOf("2", "3"), tracker.steps.value.map { it.requestId })
    }

    @Test
    fun `clear empties the history`() {
        val tracker = ExecutionTracker()
        tracker.record(step("1"))

        tracker.clear()

        assertTrue(tracker.steps.value.isEmpty())
    }
}
