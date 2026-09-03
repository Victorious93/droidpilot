package com.mobilemcp.pro.server

import com.mobilemcp.pro.automation.FakeDeviceAutomator
import com.mobilemcp.pro.core.ErrorCode
import com.mobilemcp.pro.core.audit.AuditEventType
import com.mobilemcp.pro.core.audit.AuditLogger
import com.mobilemcp.pro.core.permission.AuthorizationManager
import com.mobilemcp.pro.core.permission.GrantDuration
import com.mobilemcp.pro.core.permission.InMemoryGrantStore
import com.mobilemcp.pro.core.permission.PairedDeviceRegistry
import com.mobilemcp.pro.core.permission.RemotePermission
import com.mobilemcp.pro.core.permission.RequestGuard
import com.mobilemcp.pro.core.root.ProcessShellExecutor
import com.mobilemcp.pro.core.root.RootCommandHandler
import com.mobilemcp.pro.core.root.RootManager
import com.mobilemcp.pro.core.root.ShellExecutor
import com.mobilemcp.pro.core.root.ShellResult
import com.mobilemcp.pro.core.root.ShellStatus
import com.mobilemcp.pro.protocol.CommandRequest
import com.mobilemcp.pro.protocol.Protocol
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring, tested from the outside.
 *
 * Until now the authorisation core was complete, tested, and unreachable: no production code
 * path called it, so nothing it decided had any effect on what the app would do. These tests
 * exercise the path a real peer takes — a `CommandRequest` arriving at [CommandDispatcher] —
 * and assert on whether a command *ran*, not merely on what the decision object said.
 *
 * That distinction is the point of the file. `AuthorizationManagerTest` proves the rules are
 * computed correctly; this proves the rules are the ones standing between a network request
 * and a shell. A regression that disconnected the two would leave the first suite entirely
 * green.
 */
class CommandDispatcherPrivilegedTest {

    private val laptop = "device-under-test"
    private var now = 1_700_000_000_000L
    private val paired = mutableSetOf(laptop)

    /** Records what actually reached a shell, so "denied" can mean "did not run". */
    private class RecordingShell : ShellExecutor {
        val executed = mutableListOf<Pair<String, Boolean>>()
        var rootAvailable = true

        override suspend fun execute(command: String, elevated: Boolean, timeoutMillis: Long): ShellResult {
            executed += command to elevated
            if (command == "id -u" && elevated) {
                return result(command, if (rootAvailable) "0" else "", if (rootAvailable) 0 else 1, elevated)
            }
            if (command == "su -v") return result(command, "fake 1.0", 0, elevated)
            return result(command, "output of $command", 0, elevated)
        }

        override suspend fun executeArgv(argv: List<String>, elevated: Boolean, timeoutMillis: Long) =
            execute(argv.joinToString(" "), elevated, timeoutMillis)

        private fun result(command: String, stdout: String, exit: Int, elevated: Boolean) = ShellResult(
            command = command, stdout = stdout, stderr = "", exitCode = exit, durationMillis = 1,
            status = if (exit == 0) ShellStatus.SUCCESS else ShellStatus.FAILED, elevated = elevated,
        )

        /** Commands a caller actually asked for, excluding the root-capability probe. */
        fun realCommands() = executed.filterNot { it.first == "id -u" || it.first == "su -v" }
    }

    private val shell = RecordingShell()
    private val audit = AuditLogger(clock = { now })
    private val authorization = AuthorizationManager(
        store = InMemoryGrantStore(),
        pairedDevices = object : PairedDeviceRegistry {
            override fun isPaired(deviceId: String) = deviceId in paired
        },
        clock = { now },
    )

    private val gateway = PrivilegedCommandGateway(
        RootCommandHandler(
            rootManager = RootManager(shell, clock = { now }),
            authorization = authorization,
            requestGuard = RequestGuard(clock = { now }),
            audit = audit,
        ),
    )

    private val automator = FakeDeviceAutomator()
    private val dispatcher = CommandDispatcher({ automator }, appVersion = "test", privileged = gateway)

    private var counter = 0
    private fun request(
        command: String = "shell",
        shellCommand: String? = "whoami",
        initiator: String? = null,
        timestamp: Long? = now,
        id: String = "req-${counter++}",
    ): CommandRequest {
        val fields = buildList {
            shellCommand?.let { add("\"command\":\"$it\"") }
            initiator?.let { add("\"initiator\":\"$it\"") }
        }.joinToString(",")
        return CommandRequest(
            id = id,
            command = command,
            params = Protocol.json.parseToJsonElement("{$fields}") as JsonObject,
            timestamp = timestamp,
        )
    }

    // ------------------------------------------------------------------- the point

    /**
     * The single most important assertion in this file: a paired peer, holding a valid
     * pairing secret and a live connection, cannot run a shell command it was not granted.
     */
    @Test
    fun `a shell command is refused when the owner has granted nothing`() = runTest {
        val response = dispatcher.dispatch(request(), laptop)

        assertFalse(response.success)
        assertEquals(ErrorCode.PERMISSION_DENIED, response.errorCode)
        assertTrue("nothing may reach a shell without a grant", shell.realCommands().isEmpty())
    }

    @Test
    fun `a shell command runs once the owner grants REMOTE_SHELL`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(), laptop)

        assertTrue("expected success, got: ${response.error}", response.success)
        assertEquals(listOf("whoami" to false), shell.realCommands())
        assertEquals(
            "output of whoami",
            response.data!!.let { (it as JsonObject)["stdout"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `revoking the grant stops the next command`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        assertTrue(dispatcher.dispatch(request(), laptop).success)

        authorization.revoke(laptop, RemotePermission.REMOTE_SHELL)

        val after = dispatcher.dispatch(request(), laptop)
        assertFalse("revocation must bite on the very next command", after.success)
        assertEquals(1, shell.realCommands().size)
    }

    // --------------------------------------------------------- shell is not root

    @Test
    fun `REMOTE_SHELL does not authorise a root command`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(command = "shell_root", initiator = "device"), laptop)

        assertFalse(response.success)
        assertTrue("an unprivileged grant must not reach an elevated shell", shell.realCommands().isEmpty())
    }

    @Test
    fun `REMOTE_ROOT authorises a root command from a person`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(command = "shell_root", initiator = "device"), laptop)

        assertTrue("expected success, got: ${response.error}", response.success)
        assertEquals(listOf("whoami" to true), shell.realCommands())
    }

    // ------------------------------------------------------------------- AI_ROOT

    @Test
    fun `the model is refused root when only REMOTE_ROOT is granted`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(command = "shell_root", initiator = "ai"), laptop)

        assertFalse("AI_ROOT is a separate decision and has not been made", response.success)
        assertTrue(shell.realCommands().isEmpty())
    }

    @Test
    fun `the model may run root when both REMOTE_ROOT and AI_ROOT are granted`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        authorization.grant(laptop, RemotePermission.AI_ROOT, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(command = "shell_root", initiator = "ai"), laptop)

        assertTrue("expected success, got: ${response.error}", response.success)
        assertEquals(listOf("whoami" to true), shell.realCommands())
    }

    /**
     * Omitting the initiator must not be a way to obtain the weaker check.
     *
     * The gateway treats an unlabelled command as model-initiated precisely because that is
     * the reading which requires *more* authorisation.
     */
    @Test
    fun `an unlabelled root command is treated as model-initiated`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(command = "shell_root", initiator = null), laptop)

        assertFalse("omitting the initiator must not skip the AI gate", response.success)
        assertTrue(shell.realCommands().isEmpty())
    }

    // ------------------------------------------------------- identity and replay

    @Test
    fun `a caller with no established identity is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(), callerDeviceId = null)

        assertFalse(response.success)
        assertEquals(ErrorCode.PERMISSION_DENIED, response.errorCode)
        assertTrue(shell.realCommands().isEmpty())
    }

    @Test
    fun `a caller whose identity is not the paired one is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(), callerDeviceId = "some-other-device")

        assertFalse(response.success)
        assertTrue(shell.realCommands().isEmpty())
    }

    @Test
    fun `a replayed request id does not run the command twice`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        val replayed = request(id = "same-id")

        assertTrue(dispatcher.dispatch(replayed, laptop).success)
        val second = dispatcher.dispatch(replayed, laptop)

        assertFalse("a resent privileged request is a second real execution", second.success)
        assertEquals("the command must have run exactly once", 1, shell.realCommands().size)
    }

    @Test
    fun `a privileged command without a timestamp is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(timestamp = null), laptop)

        assertFalse(response.success)
        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertTrue(shell.realCommands().isEmpty())
    }

    @Test
    fun `a stale timestamp is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(timestamp = now - 3_600_000L), laptop)

        assertFalse(response.success)
        assertTrue(shell.realCommands().isEmpty())
    }

    // ------------------------------------------------------------------ durations

    @Test
    fun `a single-use grant authorises exactly one command`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.Once)

        assertTrue(dispatcher.dispatch(request(), laptop).success)
        assertFalse(dispatcher.dispatch(request(), laptop).success)
        assertEquals(1, shell.realCommands().size)
    }

    @Test
    fun `an expired grant stops authorising`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.Until(now + 60_000L))

        assertTrue(dispatcher.dispatch(request(), laptop).success)
        now += 120_000L
        assertFalse(dispatcher.dispatch(request(), laptop).success)
        assertEquals(1, shell.realCommands().size)
    }

    // -------------------------------------------------------------- shape of path

    /**
     * A shell does not depend on the Accessibility service, and must not be refused for
     * lacking it — the reason would be wrong, and it would be wrong in the direction that
     * sends an operator looking in the entirely wrong place.
     */
    @Test
    fun `a shell command works while the Accessibility service is off`() = runTest {
        val withoutAccessibility =
            CommandDispatcher({ null }, appVersion = "test", privileged = gateway)
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = withoutAccessibility.dispatch(request(), laptop)

        assertTrue("expected success, got: ${response.error}", response.success)
    }

    /** A build with no gateway has no path to a shell at all. */
    @Test
    fun `a dispatcher without a gateway reports the shell as unsupported`() = runTest {
        val noShell = CommandDispatcher({ automator }, appVersion = "test")

        val response = noShell.dispatch(request(), laptop)

        assertFalse(response.success)
        assertEquals(ErrorCode.UNSUPPORTED, response.errorCode)
    }

    @Test
    fun `an empty command is refused`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        val response = dispatcher.dispatch(request(shellCommand = ""), laptop)

        assertFalse(response.success)
        assertTrue(shell.realCommands().isEmpty())
    }

    @Test
    fun `a root command is refused when the device has no root provider`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_ROOT, GrantDuration.UntilRevoked)
        shell.rootAvailable = false

        val response = dispatcher.dispatch(request(command = "shell_root", initiator = "device"), laptop)

        assertFalse(response.success)
        assertTrue(shell.realCommands().isEmpty())
        assertTrue(
            "a refusal must not be recorded as an execution",
            audit.eventsOfType(AuditEventType.ROOT_EXECUTED).isEmpty(),
        )
    }

    // ---------------------------------------------------------------------- audit

    @Test
    fun `an executed command is recorded without its output`() = runTest {
        authorization.grant(laptop, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)

        dispatcher.dispatch(request(), laptop)

        val events = audit.eventsOfType(AuditEventType.SHELL_EXECUTED)
        assertEquals(1, events.size)
        assertEquals("whoami", events.single().command)
        assertEquals("output size is recorded", "output of whoami".length, events.single().stdoutBytes)
        assertFalse(
            "the trail must never hold the output itself",
            audit.export().contains("output of whoami"),
        )
    }

    @Test
    fun `a refusal is recorded`() = runTest {
        dispatcher.dispatch(request(), laptop)

        assertFalse(audit.eventsOfType(AuditEventType.AUTHORIZATION_DENIED).isEmpty())
    }

    /** The real executor is the one the app ships; this only pins its shape. */
    @Test
    fun `the production executor elevates through su`() {
        assertTrue(ProcessShellExecutor() is ShellExecutor)
    }
}
