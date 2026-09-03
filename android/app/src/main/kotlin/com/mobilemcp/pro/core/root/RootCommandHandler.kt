package com.mobilemcp.pro.core.root

import com.mobilemcp.pro.core.audit.AuditEventType
import com.mobilemcp.pro.core.audit.AuditLogger
import com.mobilemcp.pro.core.permission.AuthorizationDecision
import com.mobilemcp.pro.core.permission.AuthorizationManager
import com.mobilemcp.pro.core.permission.Initiator
import com.mobilemcp.pro.core.permission.RemotePermission
import com.mobilemcp.pro.core.permission.RequestGuard

/** A request to run a command, from a paired device or from the AI subsystem. */
data class ShellCommandRequest(
    val requestId: String,
    val deviceId: String,
    val deviceName: String?,
    val command: String,
    val elevated: Boolean,
    val initiator: Initiator,
    val timestampMillis: Long,
    val timeoutMillis: Long = ShellLimits.DEFAULT_TIMEOUT_MILLIS,
)

/**
 * The only path by which a remote peer or the AI can run a command.
 *
 * Every check named in the remote-root protocol happens here, in order, before anything is
 * executed — identity and authentication having already been established by the transport,
 * which refuses an unauthenticated peer during the WebSocket handshake.
 *
 *  1. Request is well-formed and not stale or replayed ([RequestGuard]).
 *  2. Device is paired, and holds a live grant for the permission the command needs
 *     ([AuthorizationManager] — which also enforces `AI_ROOT` for AI-initiated root).
 *  3. The device can actually do it ([RootManager.capability]).
 *  4. Execute, capture, and record.
 *
 * Ordering is deliberate. Replay is checked before authorisation so a resent request cannot
 * spend a single-use grant. Authorisation is checked before capability so that an
 * unauthorised peer learns only that it was refused, not whether this device is rooted.
 *
 * ### On not second-guessing the command
 *
 * There is no blocklist of dangerous commands, and that is the design rather than an
 * omission. Once the owner has explicitly authorised root for a device, root commands from
 * it are the feature working as intended. A blocklist would reject legitimate
 * administration while stopping nobody who can write the same thing another way, and its
 * real cost is worse: it invites the belief that the list is the security boundary. The
 * boundary is owner authorisation, device identity, and a live grant — enforced above.
 */
class RootCommandHandler(
    private val rootManager: RootManager,
    private val authorization: AuthorizationManager,
    private val requestGuard: RequestGuard,
    private val audit: AuditLogger,
) {

    sealed interface Outcome {
        data class Executed(val result: ShellResult) : Outcome
        data class Refused(val reason: String, val status: ShellStatus) : Outcome
    }

    suspend fun handle(request: ShellCommandRequest): Outcome {
        val permission =
            if (request.elevated) RemotePermission.REMOTE_ROOT else RemotePermission.REMOTE_SHELL

        // 0. Shape. Checked before the replay guard so a malformed request does not burn a
        //    request id that a corrected retry would then be refused for reusing.
        if (request.command.isBlank()) {
            audit.record(
                type = AuditEventType.AUTHORIZATION_DENIED,
                deviceId = request.deviceId,
                deviceName = request.deviceName,
                permission = permission,
                initiator = request.initiator,
                success = false,
                detail = "Empty command",
            )
            return Outcome.Refused("Empty command", ShellStatus.DENIED)
        }

        // 1. Freshness and replay. Before authorisation, so a resent request cannot consume
        //    a single-use grant just by arriving twice.
        when (val verdict = requestGuard.admit(request.requestId, request.timestampMillis)) {
            is RequestGuard.Verdict.Rejected -> {
                audit.record(
                    type = AuditEventType.AUTHORIZATION_DENIED,
                    deviceId = request.deviceId,
                    deviceName = request.deviceName,
                    permission = permission,
                    initiator = request.initiator,
                    command = request.command,
                    success = false,
                    detail = verdict.reason,
                )
                return Outcome.Refused(verdict.reason, ShellStatus.DENIED)
            }
            RequestGuard.Verdict.Fresh -> Unit
        }

        // 2. Authorisation. Enforces pairing, grant existence, revocation, expiry,
        //    single-use consumption, and AI_ROOT for AI-initiated root commands.
        when (val decision = authorization.authorize(request.deviceId, permission, request.initiator)) {
            is AuthorizationDecision.Denied -> {
                audit.record(
                    type = AuditEventType.AUTHORIZATION_DENIED,
                    deviceId = request.deviceId,
                    deviceName = request.deviceName,
                    permission = permission,
                    initiator = request.initiator,
                    command = request.command,
                    success = false,
                    detail = decision.message,
                )
                return Outcome.Refused(decision.message, ShellStatus.DENIED)
            }
            is AuthorizationDecision.Allowed -> Unit
        }

        // 3. Capability. Only after authorisation, so an unauthorised peer cannot use the
        //    difference between "denied" and "not rooted" to probe the device.
        if (request.elevated) {
            val capability = rootManager.capability()
            if (!capability.available) {
                val reason = capability.unavailableReason ?: "Root is not available on this device"
                // Recorded as a refusal, not as an execution. The owner reads this trail
                // to answer "what ran as root on my phone"; a command that was refused for
                // want of a root provider never ran, and filing it under ROOT_EXECUTED puts
                // it in that answer indistinguishably from one that did.
                audit.record(
                    type = AuditEventType.AUTHORIZATION_DENIED,
                    deviceId = request.deviceId,
                    deviceName = request.deviceName,
                    permission = permission,
                    initiator = request.initiator,
                    command = request.command,
                    success = false,
                    detail = reason,
                )
                return Outcome.Refused(reason, ShellStatus.DENIED)
            }
        }

        // 4. Execute and record. Output is recorded as sizes only — see AuditLogger.
        val result = if (request.elevated) {
            rootManager.executeAsRoot(request.command, request.timeoutMillis)
        } else {
            rootManager.execute(request.command, request.timeoutMillis)
        }

        audit.record(
            type = if (request.elevated) AuditEventType.ROOT_EXECUTED else AuditEventType.SHELL_EXECUTED,
            deviceId = request.deviceId,
            deviceName = request.deviceName,
            permission = permission,
            initiator = request.initiator,
            command = request.command,
            exitCode = result.exitCode,
            durationMillis = result.durationMillis,
            success = result.succeeded,
            stdoutBytes = result.stdout.length,
            stderrBytes = result.stderr.length,
        )

        return Outcome.Executed(result)
    }
}
