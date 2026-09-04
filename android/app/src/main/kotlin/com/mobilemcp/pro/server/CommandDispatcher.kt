package com.mobilemcp.pro.server

import android.util.Log
import com.mobilemcp.pro.agent.ActionStatus
import com.mobilemcp.pro.agent.ExecutionStep
import com.mobilemcp.pro.agent.ExecutionTracker
import com.mobilemcp.pro.automation.DeviceAutomator
import com.mobilemcp.pro.automation.ElementSelector
import com.mobilemcp.pro.automation.ScrollDirection
import com.mobilemcp.pro.automation.SystemKey
import com.mobilemcp.pro.core.ErrorCode
import com.mobilemcp.pro.core.mode.AppMode
import com.mobilemcp.pro.core.root.ShellLimits
import com.mobilemcp.pro.core.OperationResult
import com.mobilemcp.pro.protocol.CommandRequest
import com.mobilemcp.pro.protocol.CommandResponse
import com.mobilemcp.pro.protocol.Protocol
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns a decoded [CommandRequest] into a [CommandResponse].
 *
 * All the branching that used to sit inside the Accessibility service lives here instead:
 * parameter validation, capability checks, deadlines and error mapping. The dispatcher
 * depends only on [DeviceAutomator], so this — the part with the most paths through it —
 * is covered by unit tests running against a fake, with no device involved.
 *
 * Two invariants hold for every command:
 *
 *  - **Nothing runs without a deadline.** Each command carries a bound, so a request that
 *    hangs inside a platform call fails cleanly rather than pinning a coroutine and
 *    leaving the client waiting until its own timeout.
 *  - **Nothing escapes as an exception.** Unexpected throwables become
 *    [ErrorCode.INTERNAL] responses with the stack trace logged locally and only a
 *    summary sent to the peer.
 */
class CommandDispatcher(
    private val automatorProvider: () -> DeviceAutomator?,
    private val appVersion: String,
    /**
     * The authorised route to a shell, or `null` when this dispatcher has none.
     *
     * Null is the safe shape rather than a missing feature flag: with no gateway the shell
     * commands are refused as unsupported, and there is no code path from a network request
     * to an elevated shell at all.
     */
    private val privileged: PrivilegedCommandGateway? = null,
    /** What `get_capabilities` reports as the device's current operating mode. */
    private val currentMode: () -> AppMode = { AppMode.DEFAULT },
    /**
     * Records every dispatched command for the owner-facing execution history.
     *
     * Null by default so existing callers (and the tests written against them) are
     * unaffected; `ServerForegroundService` is the only production caller that supplies one.
     */
    private val tracker: ExecutionTracker? = null,
) {

    /**
     * Answers [request] on behalf of [callerDeviceId].
     *
     * The caller's identity is a property of the connection, not of the request — a peer
     * does not get to say who it is — so it is passed in by the server that authenticated
     * it rather than read out of the payload. It defaults to `null` because the ordinary
     * automation commands do not consult it; the privileged ones refuse without it.
     */
    suspend fun dispatch(request: CommandRequest, callerDeviceId: String? = null): CommandResponse {
        val startedAt = System.currentTimeMillis()
        val response = try {
            withTimeout(deadlineFor(request)) {
                execute(request, callerDeviceId)
            }
        } catch (e: TimeoutCancellationException) {
            CommandResponse.error(
                request.id,
                ErrorCode.TIMEOUT,
                "Command '${request.command}' exceeded its ${deadlineFor(request)} ms deadline",
            )
        } catch (e: Exception) {
            // The peer gets the type and message; the stack trace stays in logcat. Echoing
            // full internals to a network peer is how implementation detail leaks.
            Log.e(TAG, "Unhandled failure in '${request.command}'", e)
            CommandResponse.error(
                request.id,
                ErrorCode.INTERNAL,
                "Internal error handling '${request.command}': ${e.javaClass.simpleName}",
            )
        }
        recordExecution(request, response, startedAt)
        return response
    }

    /**
     * Appends [response] to the execution history, when one is configured.
     *
     * `ping` and `get_capabilities` are excluded: they are polling, not an action the owner
     * asked the agent to take, and including them would drown the history that matters in
     * connection-health noise.
     */
    private fun recordExecution(request: CommandRequest, response: CommandResponse, startedAt: Long) {
        val tracker = tracker ?: return
        if (request.command == "ping" || request.command == "get_capabilities") return

        val status = if (response.success) {
            ActionStatus.SUCCESS
        } else {
            response.errorCode?.let(ActionStatus::from) ?: ActionStatus.FAILED
        }

        tracker.record(
            ExecutionStep(
                requestId = request.id,
                command = request.command,
                status = status,
                summary = if (response.success) "Completed" else "Failed",
                error = response.error,
                startedAtMillis = startedAt,
                finishedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun execute(request: CommandRequest, callerDeviceId: String?): CommandResponse {
        val params = CommandParams(request.params)

        // Shell commands are answered before the Accessibility requirement below, because
        // they do not need it: a shell is a separate capability, and refusing one for want
        // of an accessibility service would be a misleading reason. They are also the only
        // commands here that consult authorisation at all.
        when (request.command) {
            "shell", "shell_root" -> {
                val gateway = privileged ?: return CommandResponse.error(
                    request.id,
                    ErrorCode.UNSUPPORTED,
                    "This build has no shell command path.",
                )
                return gateway.handle(
                    request,
                    callerDeviceId,
                    elevated = request.command == "shell_root",
                )
            }
        }

        // Commands that need no device access are answered first, so that `ping` and
        // `get_capabilities` still work when the Accessibility service is off — which is
        // exactly when a client most needs to ask what is wrong.
        when (request.command) {
            "ping" -> return CommandResponse.success(
                request.id,
                buildJsonObject {
                    put("pong", true)
                    put("protocolVersion", Protocol.VERSION)
                    put("appVersion", appVersion)
                },
            )

            "get_capabilities" -> {
                val automator = automatorProvider()
                return CommandResponse.success(
                    request.id,
                    buildJsonObject {
                        put("accessibilityConnected", automator != null)
                        put("protocolVersion", Protocol.VERSION)
                        put("appVersion", appVersion)
                        put("mode", currentMode().wireName)
                    },
                )
            }
        }

        val automator = automatorProvider() ?: return CommandResponse.error(
            request.id,
            ErrorCode.SERVICE_UNAVAILABLE,
            "The DroidPilot Accessibility service is not running. Enable it in " +
                "Settings › Accessibility › DroidPilot, then retry.",
        )

        val result: OperationResult<Any?> = when (request.command) {

            "tap" -> {
                val x = params.requireFloat("x")
                val y = params.requireFloat("y")
                val duration = params.optionalLong("duration", 100L, 1L, MAX_GESTURE_MILLIS)
                params.firstError()?.let { return invalid(request, it) }
                automator.tap(x, y, duration)
            }

            "long_press" -> {
                val x = params.requireFloat("x")
                val y = params.requireFloat("y")
                val duration = params.optionalLong("duration", 1_000L, 1L, MAX_GESTURE_MILLIS)
                params.firstError()?.let { return invalid(request, it) }
                automator.longPress(x, y, duration)
            }

            "swipe" -> {
                val startX = params.requireFloat("startX")
                val startY = params.requireFloat("startY")
                val endX = params.requireFloat("endX")
                val endY = params.requireFloat("endY")
                val duration = params.optionalLong("duration", 300L, 1L, MAX_GESTURE_MILLIS)
                params.firstError()?.let { return invalid(request, it) }
                automator.swipe(startX, startY, endX, endY, duration)
            }

            "scroll" -> {
                val raw = params.requireString("direction")
                params.firstError()?.let { return invalid(request, it) }
                val direction = runCatching { ScrollDirection.valueOf(raw.uppercase()) }.getOrNull()
                    ?: return invalid(
                        request,
                        "Unknown direction '$raw'. Expected one of: " +
                            ScrollDirection.entries.joinToString { it.name.lowercase() },
                    )
                val amount = params.optionalFloat("amount", 500f, 1f, MAX_SCROLL_PX)
                params.firstError()?.let { return invalid(request, it) }
                automator.scroll(direction, amount)
            }

            "pinch" -> {
                val centerX = params.optionalFloatOrNull("x")
                val centerY = params.optionalFloatOrNull("y")
                val scale = params.optionalFloat("scale", 1.5f, MIN_PINCH_SCALE, MAX_PINCH_SCALE)
                val duration = params.optionalLong("duration", 400L, 1L, MAX_GESTURE_MILLIS)
                params.firstError()?.let { return invalid(request, it) }
                automator.pinch(centerX, centerY, scale, duration)
            }

            "type_text", "set_text" -> {
                // `set_text` replaces, `type_text` appends. Same call, one flag.
                val text = params.requireString("text", allowBlank = true)
                params.firstError()?.let { return invalid(request, it) }
                if (text.length > MAX_TEXT_LENGTH) {
                    return invalid(request, "Text exceeds the $MAX_TEXT_LENGTH character limit")
                }
                automator.typeText(text, replace = request.command == "set_text")
            }

            "press_key" -> {
                val raw = params.requireString("key")
                params.firstError()?.let { return invalid(request, it) }
                val key = SystemKey.fromWire(raw) ?: return invalid(
                    request,
                    "Unknown key '$raw'. Expected one of: " +
                        SystemKey.entries.joinToString { it.name.lowercase() },
                )
                automator.pressKey(key)
            }

            "get_ui_tree" -> {
                val maxDepth = params.optionalInt("maxDepth", 15, 0, MAX_TREE_DEPTH)
                val maxNodes = params.optionalInt("maxNodes", DEFAULT_MAX_NODES, 1, HARD_MAX_NODES)
                params.firstError()?.let { return invalid(request, it) }
                automator.uiTree(maxDepth, maxNodes)
            }

            "find_element" -> {
                val selector = selectorFrom(params)
                val maxResults = params.optionalInt("maxResults", 10, 1, MAX_FIND_RESULTS)
                params.firstError()?.let { return invalid(request, it) }
                automator.findElements(selector, maxResults)
            }

            "click_element", "long_click_element" -> {
                val selector = selectorFrom(params)
                params.firstError()?.let { return invalid(request, it) }
                if (selector.isEmpty) {
                    // Without this guard an empty selector matches the root node, and the
                    // caller gets a successful click on something they never asked for.
                    return invalid(
                        request,
                        "At least one of 'text', 'id', 'className' or 'contentDescription' is required",
                    )
                }
                automator.clickElement(selector, longPress = request.command == "long_click_element")
            }

            "wait_for_element" -> {
                val selector = selectorFrom(params)
                val timeout = params.optionalLong("timeout", 10_000L, 100L, MAX_WAIT_MILLIS)
                params.firstError()?.let { return invalid(request, it) }
                if (selector.isEmpty) {
                    return invalid(
                        request,
                        "At least one of 'text', 'id', 'className' or 'contentDescription' is required",
                    )
                }
                automator.waitForElement(selector, timeout)
            }

            "get_focused" -> automator.focusedElement()

            "screenshot" -> {
                val quality = params.optionalInt("quality", 80, 1, 100)
                val maxDimension = params.optionalInt("maxDimension", DEFAULT_MAX_DIMENSION, 120, HARD_MAX_DIMENSION)
                params.firstError()?.let { return invalid(request, it) }
                automator.screenshot(quality, maxDimension)
            }

            "open_app" -> {
                val packageName = params.requireString("package")
                params.firstError()?.let { return invalid(request, it) }
                if (!VALID_PACKAGE.matches(packageName)) {
                    return invalid(request, "'$packageName' is not a valid Android package name")
                }
                automator.openApp(packageName)
            }

            "get_device_info" -> automator.deviceInfo()

            else -> return CommandResponse.error(
                request.id,
                ErrorCode.UNKNOWN_COMMAND,
                "Unknown command '${request.command}'",
            )
        }

        return result.toResponse(request.id)
    }

    private fun selectorFrom(params: CommandParams) = ElementSelector(
        text = params.optionalString("text"),
        viewId = params.optionalString("id"),
        className = params.optionalString("className"),
        contentDescription = params.optionalString("contentDescription"),
        exact = params.optionalBoolean("exact", false),
    )

    private fun invalid(request: CommandRequest, message: String): CommandResponse =
        CommandResponse.error(request.id, ErrorCode.INVALID_REQUEST, message)

    /**
     * Per-command deadline.
     *
     * `wait_for_element` is the one command whose duration the caller chooses, so its
     * deadline is derived from the requested timeout plus a margin — a fixed ceiling would
     * either cut short a legitimate long wait or leave every other command waiting far too
     * long before failing.
     */
    private fun deadlineFor(request: CommandRequest): Long = when (request.command) {
        "wait_for_element" -> {
            val requested = CommandParams(request.params)
                .optionalLong("timeout", 10_000L, 100L, MAX_WAIT_MILLIS)
            requested + WAIT_DEADLINE_MARGIN_MILLIS
        }
        "screenshot" -> SCREENSHOT_DEADLINE_MILLIS
        "get_ui_tree" -> TREE_DEADLINE_MILLIS
        // The shell enforces its own timeout on the process it spawns. This outer deadline
        // must therefore sit *above* it, or the dispatcher would cancel the coroutine while
        // the process was still being torn down and report a timeout for a command that had
        // in fact already been authorised and run — the worst of both, since the audit
        // record would show an execution the caller was told never happened.
        "shell", "shell_root" -> {
            val requested = CommandParams(request.params).optionalLong(
                "timeout",
                ShellLimits.DEFAULT_TIMEOUT_MILLIS,
                1_000L,
                ShellLimits.MAX_TIMEOUT_MILLIS,
            )
            requested + SHELL_DEADLINE_MARGIN_MILLIS
        }
        else -> DEFAULT_DEADLINE_MILLIS
    }

    private fun OperationResult<Any?>.toResponse(id: String): CommandResponse = when (this) {
        is OperationResult.Failure -> {
            if (detail != null) Log.d(TAG, "$code: $message ($detail)")
            CommandResponse.error(id, code, message)
        }
        is OperationResult.Success -> CommandResponse.success(id, encode(value))
    }

    /**
     * Serialises a command's payload.
     *
     * Every result type is `@Serializable`, so this is a dispatch over the known set rather
     * than reflection. `String` results are wrapped in `{"result": ...}` so that a response
     * body is always an object — clients can then read fields uniformly instead of
     * branching on whether they got an object or a bare scalar.
     */
    private fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> buildJsonObject { put("result", value) }
        is com.mobilemcp.pro.automation.UiNode ->
            Protocol.json.encodeToJsonElement(com.mobilemcp.pro.automation.UiNode.serializer(), value)
        is com.mobilemcp.pro.automation.UiTreeResult ->
            Protocol.json.encodeToJsonElement(com.mobilemcp.pro.automation.UiTreeResult.serializer(), value)
        is com.mobilemcp.pro.automation.WaitResult ->
            Protocol.json.encodeToJsonElement(com.mobilemcp.pro.automation.WaitResult.serializer(), value)
        is com.mobilemcp.pro.automation.Screenshot ->
            Protocol.json.encodeToJsonElement(com.mobilemcp.pro.automation.Screenshot.serializer(), value)
        is com.mobilemcp.pro.automation.DeviceInfo ->
            Protocol.json.encodeToJsonElement(com.mobilemcp.pro.automation.DeviceInfo.serializer(), value)
        is List<*> -> buildJsonObject {
            @Suppress("UNCHECKED_CAST")
            val nodes = value as List<com.mobilemcp.pro.automation.UiNode>
            put(
                "elements",
                Protocol.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.mobilemcp.pro.automation.UiNode.serializer()
                    ),
                    nodes,
                ),
            )
            put("count", nodes.size)
        }
        else -> JsonObject(mapOf("result" to JsonPrimitive(value.toString())))
    }

    private companion object {
        const val TAG = "CommandDispatcher"

        const val DEFAULT_DEADLINE_MILLIS = 20_000L
        const val SCREENSHOT_DEADLINE_MILLIS = 20_000L
        const val TREE_DEADLINE_MILLIS = 30_000L
        const val WAIT_DEADLINE_MARGIN_MILLIS = 5_000L

        /** Head-room over the shell's own timeout, for process teardown and capture. */
        const val SHELL_DEADLINE_MARGIN_MILLIS = 15_000L

        const val MAX_GESTURE_MILLIS = 60_000L
        const val MAX_WAIT_MILLIS = 300_000L
        const val MAX_SCROLL_PX = 10_000f
        const val MIN_PINCH_SCALE = 0.1f
        const val MAX_PINCH_SCALE = 10f
        const val MAX_TEXT_LENGTH = 10_000
        const val MAX_TREE_DEPTH = 50
        const val DEFAULT_MAX_NODES = 3_000
        const val HARD_MAX_NODES = 20_000
        const val MAX_FIND_RESULTS = 200
        const val DEFAULT_MAX_DIMENSION = 1_600
        const val HARD_MAX_DIMENSION = 4_096

        /** Mirrors the platform's package-name grammar. */
        val VALID_PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
