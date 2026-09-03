package com.mobilemcp.pro.core.root

import com.mobilemcp.pro.core.audit.AuditEventType
import com.mobilemcp.pro.core.audit.AuditLogger
import com.mobilemcp.pro.core.permission.AuthorizationManager
import com.mobilemcp.pro.core.permission.GrantDuration
import com.mobilemcp.pro.core.permission.InMemoryGrantStore
import com.mobilemcp.pro.core.permission.Initiator
import com.mobilemcp.pro.core.permission.PairedDeviceRegistry
import com.mobilemcp.pro.core.permission.RemotePermission
import com.mobilemcp.pro.core.permission.RequestGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks on the only path by which a remote peer can run a command.
 *
 * A [FakeShellExecutor] stands in for the process layer so the authorisation sequence can
 * be tested exhaustively, including the cases where a command must *not* run — which is the
 * half that cannot be verified by watching a real shell succeed.
 */
class RootCommandHandlerTest {

    private var now = 5_000_000L
    private val laptop = "device-laptop"
    private val paired = mutableSetOf(laptop)

    private val registry = object : PairedDeviceRegistry {
        override fun isPaired(deviceId: String) = deviceId in paired
    }

    /** Records what it was asked to run, and answers as configured. */
    private class FakeShellExecutor(var rootAvailable: Boolean = true) : ShellExecutor {
        val executed = mutableListOf<Pair<String, Boolean>>()

        override suspend fun execute(command: String, elevated: Boolean, timeoutMillis: Long): ShellResult {
            executed += command to elevated
            // The capability probe: `id -u` returns 0 only when elevation truly succeeded.
            if (command == "id -u" && elevated) {
                return result(command, if (rootAvailable) "0" else "", if (rootAvailable) 0 else 1, elevated)
            }
            if (command == "su -v") return result(command, "fake-root 1.0", 0, elevated)
            return result(command, "ok", 0, elevated)
        }

        override suspend fun executeArgv(argv: List<String>, elevated: Boolean, timeoutMillis: Long) =
            execute(argv.joinToString(" "), elevated, timeoutMillis)

        private fun result(command: String, stdout: String, exit: Int, elevated: Boolean) = ShellResult(
            command = command, stdout = stdout, stderr = "", exitCode = exit,
            durationMillis = 1, status = if (exit == 0) ShellStatus.SUCCESS else ShellStatus.FAILED,
            elevated = elevated,
        )

        /** Commands the caller actually asked for, excluding capability probing. */
        fun realCommands() = executed.filterNot { it.first == "id -u" || it.first == "su -v" }
    }

    private val executor = FakeShellExecutor()
    private val audit = AuditLogger(clock = { now })
    private val authorization = AuthorizationManager(InMemoryGrantStore(), registry, clock = { now })
    private val handler = RootCommandHandler(
        rootManager = RootManager(executor, clock = { now }),
        authorization = authorization,
        requestGuard = RequestGuard(clock = { now }),
        audit = audit,
    )

    private var requestCounter = 0
    private fun request(
        command: String = "whoami",
        elevated: Boolean = true,
        initiator: Initiator = Initiator.REMOTE_DEVICE,
        deviceId: String = laptop,
        id: String = "req-${requestCounter++}",
    ) = ShellCommandRequest(
        requestId = id,
        deviceId = deviceId,
        deviceName = "My Laptop",
        command = command,
        elevated = elevated,
        initiator = initiator,
        timestampMillis = now,
    )

    // ------------------------------------------------------------------- refusals

    @Test
    fun `root is refused without a grant`() = runTest {
        val outcome = handler.handle(request())

        assertTrue(outcome is RootCommandHandler.Outcome.Refused)
        assertTrue(executor.realCommands().isEmpty())
    }

    @Test
    fun `root is refused for an unpaired device`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        paired -= laptop

        assertTrue(handler.handle(request()) is RootCommandHandler.Outcome.Refused)
        assertTrue("nothing may run for an unpaired device", executor.realCommands().isEmpty())
    }

    @Test
    fun `root is refused after revocation`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        assertTrue(handler.handle(request()) is RootCommandHandler.Outcome.Executed)

        authorization.revoke(laptop, RemotePermission.REMOTE_ROOT)

        assertTrue(handler.handle(request()) is RootCommandHandler.Outcome.Refused)
        assertEquals("only the first command ran", 1, executor.realCommands().size)
    }

    @Test
    fun `root is refused after expiry`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Until(now + 60_000))
        assertTrue(handler.handle(request()) is RootCommandHandler.Outcome.Executed)

        now += 120_000

        assertTrue(handler.handle(request()) is RootCommandHandler.Outcome.Refused)
        assertEquals(1, executor.realCommands().size)
    }

    @Test
    fun `a single-use grant permits exactly one command`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Once)

        assertTrue(handler.handle(request()) is RootCommandHandler.Outcome.Executed)
        assertTrue(handler.handle(request()) is RootCommandHandler.Outcome.Refused)
        assertEquals(1, executor.realCommands().size)
    }

    // ---------------------------------------------------------------------- replay

    /** A resent request is a second real execution, so it must be refused. */
    @Test
    fun `a replayed request id is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        val replayed = request(id = "req-fixed")

        assertTrue(handler.handle(replayed) is RootCommandHandler.Outcome.Executed)
        assertTrue(handler.handle(replayed) is RootCommandHandler.Outcome.Refused)
        assertEquals("a replay must not run twice", 1, executor.realCommands().size)
    }

    /**
     * Replay is checked before authorisation, so a resent request cannot spend the owner's
     * single-use grant merely by arriving a second time.
     */
    @Test
    fun `a replay does not consume a single-use grant`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.Once)
        val first = request(id = "req-fixed")

        handler.handle(first)      // consumes the grant legitimately
        handler.handle(first)      // replay: refused for being a replay

        // A fresh request is refused because the grant is spent, not because of the replay.
        assertTrue(handler.handle(request(id = "req-new")) is RootCommandHandler.Outcome.Refused)
        assertEquals(1, executor.realCommands().size)
    }

    @Test
    fun `a request with a distant timestamp is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        val stale = request().copy(timestampMillis = now - 10 * 60_000)

        assertTrue(handler.handle(stale) is RootCommandHandler.Outcome.Refused)
        assertTrue(executor.realCommands().isEmpty())
    }

    // --------------------------------------------------------------------- AI_ROOT

    @Test
    fun `the AI is refused root without AI_ROOT`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        assertTrue(handler.handle(request(initiator = Initiator.AI)) is RootCommandHandler.Outcome.Refused)
        assertTrue(executor.realCommands().isEmpty())
    }

    @Test
    fun `the AI may run root with both permissions`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        authorization.grant(laptop, RemotePermission.AI_ROOT, GrantDuration.UntilRevoked)

        assertTrue(handler.handle(request(initiator = Initiator.AI)) is RootCommandHandler.Outcome.Executed)
    }

    // ------------------------------------------------------------------ capability

    /**
     * Capability is probed only after authorisation, so an unauthorised peer cannot learn
     * whether the device is rooted from the difference between the two refusals.
     */
    @Test
    fun `an unauthorised peer is not told whether the device is rooted`() = runTest {
        executor.rootAvailable = false
        val unauthorised = handler.handle(request()) as RootCommandHandler.Outcome.Refused

        executor.rootAvailable = true
        val alsoUnauthorised = handler.handle(request()) as RootCommandHandler.Outcome.Refused

        assertEquals(
            "the refusal must not vary with root availability",
            unauthorised.reason,
            alsoUnauthorised.reason,
        )
    }

    @Test
    fun `an authorised request on an unrooted device is refused honestly`() = runTest {
        executor.rootAvailable = false
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        val outcome = handler.handle(request()) as RootCommandHandler.Outcome.Refused

        assertTrue("the reason should name root", outcome.reason.contains("root", ignoreCase = true))
        assertTrue(executor.realCommands().isEmpty())
    }

    @Test
    fun `an unprivileged shell command needs no root`() = runTest {
        executor.rootAvailable = false
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        assertTrue(handler.handle(request(elevated = false)) is RootCommandHandler.Outcome.Executed)
    }

    @Test
    fun `a shell grant does not authorise a root command`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        assertTrue(handler.handle(request(elevated = true)) is RootCommandHandler.Outcome.Refused)
        assertTrue(executor.realCommands().isEmpty())
    }

    @Test
    fun `an empty command is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        assertTrue(handler.handle(request(command = "   ")) is RootCommandHandler.Outcome.Refused)
    }

    // ----------------------------------------------------------------------- audit

    @Test
    fun `a root execution is recorded`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        handler.handle(request(command = "id"))

        val recorded = audit.eventsOfType(AuditEventType.ROOT_EXECUTED)
        assertEquals(1, recorded.size)
        assertEquals("id", recorded.single().command)
        assertEquals(laptop, recorded.single().deviceId)
    }

    @Test
    fun `a refusal is recorded too`() = runTest {
        handler.handle(request())
        assertTrue(audit.eventsOfType(AuditEventType.AUTHORIZATION_DENIED).isNotEmpty())
    }

    /** Output is recorded as sizes only — never as content that could hold a secret. */
    @Test
    fun `command output is never written to the audit log`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        handler.handle(request(command = "cat /secret"))

        val event = audit.eventsOfType(AuditEventType.ROOT_EXECUTED).single()
        assertEquals("only the size is stored", 2, event.stdoutBytes) // FakeShellExecutor returns "ok"
        assertFalse("the export must not contain output", audit.export().contains("ok\n"))
    }

    /**
     * Once the owner has authorised root, DroidPilot runs what it is told. There is no
     * blocklist second-guessing the command — the boundary is the authorisation above.
     */
    @Test
    fun `an authorised root command is executed verbatim`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        val outcome = handler.handle(request(command = "mount -o remount,rw /system"))

        assertTrue(outcome is RootCommandHandler.Outcome.Executed)
        assertEquals("mount -o remount,rw /system" to true, executor.realCommands().single())
    }

    @Test
    fun `a secret-bearing flag is redacted in the audit log`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        handler.handle(request(command = "some-tool --token hunter2 --verbose"))

        val recorded = audit.eventsOfType(AuditEventType.ROOT_EXECUTED).single().command!!
        assertFalse(recorded.contains("hunter2"))
        assertTrue(recorded.contains("«redacted»"))
        assertTrue("the rest of the command survives", recorded.contains("--verbose"))
    }

    @Test
    fun `the executed command is unaffected by audit redaction`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        handler.handle(request(command = "some-tool --token hunter2"))

        assertEquals(
            "redaction is for the log only; the real command must run intact",
            "some-tool --token hunter2",
            executor.realCommands().single().first,
        )
    }

    @Test
    fun `the capability probe is not itself audited as a command`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        handler.handle(request(command = "id"))

        assertNull(audit.events.value.firstOrNull { it.command == "id -u" })
    }
}
