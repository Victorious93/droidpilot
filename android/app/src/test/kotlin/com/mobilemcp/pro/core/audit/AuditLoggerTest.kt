package com.mobilemcp.pro.core.audit

import com.mobilemcp.pro.core.permission.Initiator
import com.mobilemcp.pro.core.permission.RemotePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit trail is the owner's only account of what was done with their device's
 * privileges. Its value depends entirely on being accurate and on never becoming a second
 * copy of the secrets it describes, so both properties are tested rather than assumed.
 */
class AuditLoggerTest {

    private var now = 1_700_000_000_000L
    private val logger = AuditLogger(clock = { now })

    // ------------------------------------------------------------------- accuracy

    /**
     * Clearing the log is an owner action worth recording, but it is not a revocation.
     *
     * Recording it as one puts an event in the trail that never happened, and — worse —
     * makes it show up when the owner filters for revocations to answer "when did I take
     * this device's access away?".
     */
    @Test
    fun `clearing the log is recorded as its own event, not as a revocation`() {
        logger.record(AuditEventType.ROOT_EXECUTED, command = "id")
        logger.clear()

        assertTrue(
            "clearing the log is not a permission change and must not be filed as one",
            logger.eventsOfType(AuditEventType.PERMISSION_REVOKED).isEmpty(),
        )
        assertEquals(
            "the clear itself must remain in the trail",
            1,
            logger.eventsOfType(AuditEventType.AUDIT_CLEARED).size,
        )
    }

    @Test
    fun `clearing removes the previous entries`() {
        repeat(5) { logger.record(AuditEventType.SHELL_EXECUTED, command = "echo $it") }
        logger.clear()

        assertTrue(logger.eventsOfType(AuditEventType.SHELL_EXECUTED).isEmpty())
    }

    @Test
    fun `events are capped so a busy device cannot exhaust memory`() {
        val bounded = AuditLogger(maxEntries = 10, clock = { now })
        repeat(50) { bounded.record(AuditEventType.SHELL_EXECUTED, command = "echo $it") }

        assertEquals(10, bounded.events.value.size)
        assertTrue(
            "the cap must keep the most recent entries, not the oldest",
            bounded.events.value.last().command!!.endsWith("49"),
        )
    }

    @Test
    fun `ids are unique even within the same millisecond`() {
        val ids = (1..100).map { logger.record(AuditEventType.SHELL_EXECUTED, command = "x").id }
        assertEquals("every event needs a distinct id to be citable", ids.size, ids.toSet().size)
    }

    @Test
    fun `an event carries the fields an owner needs to interpret it`() {
        val event = logger.record(
            type = AuditEventType.ROOT_EXECUTED,
            deviceId = "device-laptop",
            deviceName = "My Laptop",
            permission = RemotePermission.REMOTE_ROOT,
            initiator = Initiator.AI,
            command = "id -u",
            exitCode = 0,
            success = true,
        )

        assertEquals(now, event.timestampMillis)
        assertEquals(RemotePermission.REMOTE_ROOT, event.permission)
        assertEquals(Initiator.AI, event.initiator)
        assertTrue("AI-initiated work must be visible at a glance", "AI-initiated" in event.describe())
    }

    // ------------------------------------------------------------------ discretion

    /** Output is the field most likely to contain a secret, so it is never stored. */
    @Test
    fun `command output is recorded only as a size`() {
        val event = logger.record(
            type = AuditEventType.ROOT_EXECUTED,
            command = "cat /data/secret",
            stdoutBytes = 4096,
            stderrBytes = 0,
        )

        assertEquals(4096, event.stdoutBytes)
        assertNotNull(event.command)
        assertFalse(
            "no field may carry command output",
            listOfNotNull(event.command, event.detail).any { it.contains("secret-contents") },
        )
    }

    @Test
    fun `secrets passed as conventional flags are redacted`() {
        val event = logger.record(
            type = AuditEventType.SHELL_EXECUTED,
            command = "curl -X POST --token hunter2 --api-key=abcd1234 https://example.test",
        )

        val recorded = event.command!!
        assertFalse("the token argument must not be stored", recorded.contains("hunter2"))
        assertFalse("the api key must not be stored", recorded.contains("abcd1234"))
        assertTrue("the shape of the command must survive redaction", recorded.contains("--token"))
        assertTrue(recorded.contains("https://example.test"))
    }

    @Test
    fun `redaction survives export`() {
        logger.record(AuditEventType.SHELL_EXECUTED, command = "login --password swordfish")
        assertFalse(
            "export must not undo redaction — it is the form most likely to be shared",
            logger.export().contains("swordfish"),
        )
    }

    /**
     * A long command is truncated rather than stored whole, so a single pathological entry
     * cannot dominate the log.
     */
    @Test
    fun `an enormous command is truncated`() {
        val event = logger.record(AuditEventType.SHELL_EXECUTED, command = "x".repeat(50_000))
        assertTrue(event.command!!.length <= 2_000)
    }
}
