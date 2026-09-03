package com.mobilemcp.pro.server

import com.mobilemcp.pro.core.ErrorCode
import com.mobilemcp.pro.core.permission.Initiator
import com.mobilemcp.pro.core.root.RootCommandHandler
import com.mobilemcp.pro.core.root.ShellCommandRequest
import com.mobilemcp.pro.core.root.ShellLimits
import com.mobilemcp.pro.protocol.CommandRequest
import com.mobilemcp.pro.protocol.CommandResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bridge between a wire command and the authorisation core.
 *
 * This is the piece that was missing. [RootCommandHandler] and everything beneath it were
 * complete and tested, but no production code path called them, so the model they implement
 * guarded nothing. Every privileged command now enters here and leaves through the handler,
 * which enforces, in order: request freshness and replay, pairing, an unexpired unrevoked
 * grant for the permission the command needs, `AI_ROOT` on top of `REMOTE_ROOT` when the
 * initiator is the model, root availability, and an audit record either way.
 *
 * Nothing else in the app can reach an elevated shell: `RootManager` is the only class that
 * elevates, and the only caller that reaches it with a command from the network is the
 * handler behind this gateway.
 *
 * ### On the initiator being self-declared
 *
 * `AI_ROOT` exists so an owner can say "my laptop may run root commands, but the model may
 * not". The distinction rests on the `initiator` field, which the *peer* supplies — so it is
 * worth being exact about what it does and does not defend against.
 *
 * It is a policy control between an honest client and its owner, not a boundary against a
 * hostile one. A peer that holds the pairing secret and chooses to lie about its initiator
 * can already do everything the secret permits; the secret is the authority, and no field
 * inside an authenticated session can constrain a peer that is willing to forge it.
 *
 * What makes it useful in practice is that DroidPilot's own MCP server declares every root
 * command as [Initiator.AI], because by construction every command reaching it originates
 * from a model. So for the deployment this project actually ships, `AI_ROOT` is enforced
 * truthfully and `REMOTE_ROOT` alone is not enough to let Claude run a root command — which
 * is the property the owner is being offered.
 */
class PrivilegedCommandGateway(
    private val handler: RootCommandHandler,
) {

    /**
     * Runs a shell command on behalf of [callerDeviceId], or explains why it will not.
     *
     * Every refusal is a `CommandResponse`, never an exception: a peer that sends a
     * malformed privileged request gets a reason it can act on, and the connection survives.
     */
    suspend fun handle(
        request: CommandRequest,
        callerDeviceId: String?,
        elevated: Boolean,
    ): CommandResponse {
        // No identity, no authorisation. This cannot happen on the live path — the server
        // derives the caller from the credential the peer proved during the upgrade — but
        // an unauthenticated call would otherwise fall through to the handler with a blank
        // device id, and "" is not paired, so it would be refused there anyway. Refusing
        // here as well means the reason says what is actually wrong.
        if (callerDeviceId.isNullOrBlank()) {
            return CommandResponse.error(
                request.id,
                ErrorCode.PERMISSION_DENIED,
                "This connection has no established device identity, so no privileged " +
                    "command can be authorised on it.",
            )
        }

        val params = CommandParams(request.params)
        val command = params.requireString("command")
        val timeout = params.optionalLong(
            "timeout",
            ShellLimits.DEFAULT_TIMEOUT_MILLIS,
            1_000L,
            ShellLimits.MAX_TIMEOUT_MILLIS,
        )
        params.firstError()?.let {
            return CommandResponse.error(request.id, ErrorCode.INVALID_REQUEST, it)
        }

        // A timestamp is mandatory here and optional everywhere else, deliberately. The
        // replay guard rejects a request whose clock is far from this device's, and a
        // command with no timestamp cannot be checked for staleness at all — so for the one
        // command class where a re-execution is a second real action rather than an
        // idempotent retry, its absence is a refusal rather than a skipped check.
        val timestamp = request.timestamp
            ?: return CommandResponse.error(
                request.id,
                ErrorCode.INVALID_REQUEST,
                "A privileged command must carry a 'timestamp' (epoch milliseconds) so it " +
                    "can be checked for staleness and replay.",
            )

        val initiator = when ((params.optionalString("initiator") ?: DEFAULT_INITIATOR).lowercase()) {
            "ai" -> Initiator.AI
            "device", "remote_device", "human" -> Initiator.REMOTE_DEVICE
            else -> return CommandResponse.error(
                request.id,
                ErrorCode.INVALID_REQUEST,
                "Unknown 'initiator'. Use 'ai' for a model-initiated command, or 'device' " +
                    "for one a person issued.",
            )
        }

        val outcome = handler.handle(
            ShellCommandRequest(
                requestId = request.id,
                deviceId = callerDeviceId,
                deviceName = null,
                command = command,
                elevated = elevated,
                initiator = initiator,
                timestampMillis = timestamp,
                timeoutMillis = timeout,
            ),
        )

        return when (outcome) {
            is RootCommandHandler.Outcome.Refused -> CommandResponse.error(
                request.id,
                ErrorCode.PERMISSION_DENIED,
                outcome.reason,
            )

            is RootCommandHandler.Outcome.Executed -> {
                val result = outcome.result
                CommandResponse.success(
                    request.id,
                    buildJsonObject {
                        put("command", result.command)
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("exitCode", result.exitCode)
                        put("durationMillis", result.durationMillis)
                        put("status", result.status.name)
                        put("elevated", result.elevated)
                        put("truncated", result.truncated)
                    },
                )
            }
        }
    }

    private companion object {
        /**
         * Unlabelled commands are treated as model-initiated.
         *
         * The safe default, because it is the one that requires *more* authorisation: an
         * unlabelled root command needs `AI_ROOT` as well as `REMOTE_ROOT`. Defaulting the
         * other way would let a client obtain the weaker check simply by omitting a field.
         */
        const val DEFAULT_INITIATOR = "ai"
    }
}
