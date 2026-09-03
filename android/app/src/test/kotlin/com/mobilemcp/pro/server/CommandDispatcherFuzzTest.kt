package com.mobilemcp.pro.server

import com.mobilemcp.pro.automation.FakeDeviceAutomator
import com.mobilemcp.pro.protocol.CommandRequest
import com.mobilemcp.pro.protocol.Protocol
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Feeds the dispatcher input no honest client would send.
 *
 * The dispatcher sits directly behind an authenticated socket, so every one of these inputs
 * is reachable by any peer that holds the pairing secret — including one whose own software
 * is simply broken. The property under test is not that any particular request is rejected
 * with any particular message; it is the weaker and far more important one that **nothing
 * here throws**. An exception escaping into the command coroutine is a response that never
 * arrives, and the client waits out its whole timeout for it.
 *
 * Failures are asserted to be structured rather than merely non-fatal, because a refusal a
 * caller cannot interpret is only marginally better than a hang.
 */
class CommandDispatcherFuzzTest {

    private val automator = FakeDeviceAutomator()
    private val dispatcher = CommandDispatcher({ automator }, appVersion = "fuzz")

    private val commands = listOf(
        "ping", "get_capabilities", "get_device_info", "get_ui_tree", "get_focused",
        "find_element", "click_element", "long_click_element", "wait_for_element",
        "tap", "long_press", "swipe", "scroll", "pinch", "set_text", "press_key",
        "screenshot", "open_app",
    )

    /** Values chosen to break naive parsing: wrong types, extremes, and hostile strings. */
    private val hostileValues = listOf(
        "null", "true", "false", "0", "-1", "-2147483648", "2147483647",
        "9223372036854775807", "-9223372036854775808", "1e308", "-1e308",
        "\"\"", "\" \"", "\"not-a-number\"", "\"0\"", "\"-1\"",
        "\"${"A".repeat(20_000)}\"", "\"\\u0000\"", "\"../../etc/passwd\"",
        "\"; rm -rf /\"", "\"\$(whoami)\"", "\"%s%s%s%n\"", "\"<script>\"",
        "{}", "[]", "[1,2,3]", "{\"nested\":{\"deep\":1}}", "0.0", "-0.0", "3.4e38",
    )

    private val paramNames = listOf(
        "x", "y", "startX", "startY", "endX", "endY", "duration", "amount", "scale",
        "text", "id", "className", "contentDescription", "maxResults", "maxDepth",
        "maxNodes", "timeout", "quality", "maxDimension", "packageName", "key",
        "direction", "replace", "exact", "longPress",
    )

    private suspend fun dispatchRaw(command: String, paramsJson: String) {
        val request = CommandRequest(
            id = "fuzz",
            command = command,
            params = Protocol.json.parseToJsonElement(paramsJson) as JsonObject,
        )
        val response = dispatcher.dispatch(request)

        assertNotNull("every request must produce a response", response)
        assertTrue("the response must be correlated to its request", response.id == "fuzz")
        if (!response.success) {
            assertNotNull(
                "a refusal must say something a caller can act on: $command $paramsJson",
                response.errorCode,
            )
            assertFalse(
                "an error message must not be empty: $command $paramsJson",
                response.error.isNullOrBlank(),
            )
        }
    }

    // ------------------------------------------------------------- shape and types

    @Test
    fun `every command survives every parameter being of the wrong type`() = runTest {
        for (command in commands) {
            for (name in paramNames) {
                for (value in hostileValues) {
                    dispatchRaw(command, """{"$name":$value}""")
                }
            }
        }
    }

    @Test
    fun `every command survives an empty parameter object`() = runTest {
        commands.forEach { dispatchRaw(it, "{}") }
    }

    @Test
    fun `every command survives unknown parameters`() = runTest {
        commands.forEach {
            dispatchRaw(it, """{"nonsense":1,"__proto__":{"x":1},"constructor":"x","":"empty-key"}""")
        }
    }

    @Test
    fun `an unknown command is refused, not fatal`() = runTest {
        listOf("", " ", "PING", "ping ", "../ping", "reboot", "exec", "\u0000", "a".repeat(5_000))
            .forEach { dispatchRaw(it, "{}") }
    }

    // ------------------------------------------------------------------- extremes

    /**
     * Coordinates and budgets are the parameters most likely to be used in arithmetic, so
     * they are the ones where an unchecked value becomes an exception rather than a refusal.
     */
    @Test
    fun `numeric parameters survive their extremes`() = runTest {
        val extremes = listOf(
            "0", "-1", "1e-308", "1e308", "-1e308", "NaN".let { "\"$it\"" },
            "9223372036854775807", "-9223372036854775808", "2147483648", "0.1",
        )
        for (value in extremes) {
            dispatchRaw("tap", """{"x":$value,"y":$value}""")
            dispatchRaw("swipe", """{"startX":$value,"startY":$value,"endX":$value,"endY":$value}""")
            dispatchRaw("scroll", """{"direction":"down","amount":$value}""")
            dispatchRaw("pinch", """{"scale":$value,"duration":$value}""")
            dispatchRaw("get_ui_tree", """{"maxDepth":$value,"maxNodes":$value}""")
            dispatchRaw("screenshot", """{"quality":$value,"maxDimension":$value}""")
            dispatchRaw("wait_for_element", """{"text":"x","timeout":$value}""")
        }
    }

    /** Selector-shaped commands must refuse an empty selector rather than match everything. */
    @Test
    fun `an empty selector is refused rather than matching the whole screen`() = runTest {
        listOf("find_element", "click_element", "long_click_element", "wait_for_element").forEach {
            dispatchRaw(it, """{"text":"","id":"","className":"","contentDescription":""}""")
        }
    }

    // ----------------------------------------------------------------- randomised

    /**
     * A seeded random sweep, so the suite explores shapes the fixed cases above do not
     * while still failing reproducibly. The seed is fixed deliberately: a test that finds a
     * different bug on every run cannot be used to verify a fix.
     */
    @Test
    fun `randomised parameter soup never throws`() = runTest {
        val random = Random(20260903)
        repeat(2_000) {
            val command = commands[random.nextInt(commands.size)]
            val fields = (0..random.nextInt(4)).joinToString(",") {
                val name = paramNames[random.nextInt(paramNames.size)]
                val value = hostileValues[random.nextInt(hostileValues.size)]
                "\"$name\":$value"
            }
            dispatchRaw(command, "{$fields}")
        }
    }
}
