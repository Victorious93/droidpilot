package com.mobilemcp.pro.server

import android.util.Log
import com.mobilemcp.pro.automation.DeviceAutomator
import com.mobilemcp.pro.automation.ElementSelector
import com.mobilemcp.pro.automation.ScrollDirection
import com.mobilemcp.pro.automation.SystemKey
import com.mobilemcp.pro.core.ErrorCode
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
) {

    suspend fun dispatch(request: CommandRequest): CommandResponse {
        return try {
            withTimeout(deadlineFor(request)) {
                execute(request)
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
    }

    private suspend fun execute(request: CommandRequest): CommandResponse {
        val params = CommandParams(request.params)

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
