package com.mobilemcp.pro.server

import com.mobilemcp.pro.automation.ElementSelector
import com.mobilemcp.pro.automation.FakeDeviceAutomator
import com.mobilemcp.pro.automation.ScrollDirection
import com.mobilemcp.pro.automation.SystemKey
import com.mobilemcp.pro.automation.UiNode
import com.mobilemcp.pro.core.ErrorCode
import com.mobilemcp.pro.protocol.CommandRequest
import com.mobilemcp.pro.protocol.CommandResponse
import com.mobilemcp.pro.protocol.Protocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandDispatcherTest {

    private val automator = FakeDeviceAutomator()
    private val dispatcher = CommandDispatcher({ automator }, appVersion = "2.0.0")

    private fun request(command: String, params: String = "{}") = CommandRequest(
        id = "req-1",
        command = command,
        params = Protocol.json.parseToJsonElement(params).jsonObject,
    )

    private suspend fun dispatch(command: String, params: String = "{}"): CommandResponse =
        dispatcher.dispatch(request(command, params))

    // -------------------------------------------------------------- happy paths

    @Test
    fun `tap forwards coordinates and defaults the duration`() = runTest {
        val response = dispatch("tap", """{"x": 540, "y": 1200}""")

        assertTrue(response.success)
        assertEquals("req-1", response.id)
        val call = automator.callNamed("tap")!!
        assertEquals(540f, call.args["x"])
        assertEquals(1200f, call.args["y"])
        assertEquals(100L, call.args["duration"])
    }

    @Test
    fun `swipe forwards all four coordinates`() = runTest {
        dispatch("swipe", """{"startX":10,"startY":20,"endX":30,"endY":40,"duration":250}""")

        val call = automator.callNamed("swipe")!!
        assertEquals(10f, call.args["startX"])
        assertEquals(20f, call.args["startY"])
        assertEquals(30f, call.args["endX"])
        assertEquals(40f, call.args["endY"])
        assertEquals(250L, call.args["duration"])
    }

    @Test
    fun `scroll parses the direction case-insensitively`() = runTest {
        assertTrue(dispatch("scroll", """{"direction":"DOWN"}""").success)
        assertEquals(ScrollDirection.DOWN, automator.callNamed("scroll")!!.args["direction"])
    }

    @Test
    fun `press_key accepts the recents alias`() = runTest {
        assertTrue(dispatch("press_key", """{"key":"recent"}""").success)
        assertEquals(SystemKey.RECENTS, automator.callNamed("pressKey")!!.args["key"])
    }

    /** `type_text` appends, `set_text` replaces — one automator call, distinguished by a flag. */
    @Test
    fun `type_text appends and set_text replaces`() = runTest {
        dispatch("type_text", """{"text":"hello"}""")
        assertEquals(false, automator.callNamed("typeText")!!.args["replace"])

        automator.calls.clear()
        dispatch("set_text", """{"text":"hello"}""")
        assertEquals(true, automator.callNamed("typeText")!!.args["replace"])
    }

    @Test
    fun `find_element builds a selector from every criterion`() = runTest {
        dispatch(
            "find_element",
            """{"text":"Send","id":"btn","className":"Button","contentDescription":"Send it","exact":true}""",
        )

        val selector = automator.callNamed("findElements")!!.args["selector"] as ElementSelector
        assertEquals("Send", selector.text)
        assertEquals("btn", selector.viewId)
        assertEquals("Button", selector.className)
        assertEquals("Send it", selector.contentDescription)
        assertTrue(selector.exact)
    }

    @Test
    fun `find_element results are wrapped with a count`() = runTest {
        automator.elementsToReturn = listOf(UiNode(text = "one"), UiNode(text = "two"))

        val data = dispatch("find_element", """{"text":"o"}""").data!!.jsonObject
        assertEquals(2, data["count"]!!.jsonPrimitive.content.toInt())
        assertNotNull(data["elements"])
    }

    @Test
    fun `long_click_element sets the long press flag`() = runTest {
        dispatch("long_click_element", """{"text":"Item"}""")
        assertEquals(true, automator.callNamed("clickElement")!!.args["longPress"])
    }

    @Test
    fun `string results are wrapped in an object`() = runTest {
        // Always returning an object means clients read `data.result` uniformly instead of
        // branching on whether they were handed a bare scalar.
        val data = dispatch("tap", """{"x":1,"y":2}""").data!!.jsonObject
        assertNotNull(data["result"])
    }

    @Test
    fun `get_focused reports null when nothing is focused`() = runTest {
        automator.focused = null
        val response = dispatch("get_focused")

        assertTrue(response.success)
        assertTrue(response.data is kotlinx.serialization.json.JsonNull)
    }

    // ------------------------------------------------------ commands without a device

    /**
     * `ping` and `get_capabilities` must answer even with the Accessibility service off —
     * that is precisely the moment a client needs to ask what is wrong.
     */
    @Test
    fun `ping succeeds without an accessibility service`() = runTest {
        val offline = CommandDispatcher({ null }, appVersion = "2.0.0")
        val response = offline.dispatch(request("ping"))

        assertTrue(response.success)
        assertEquals(true, response.data!!.jsonObject["pong"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `get_capabilities reports that accessibility is disconnected`() = runTest {
        val offline = CommandDispatcher({ null }, appVersion = "2.0.0")
        val data = offline.dispatch(request("get_capabilities")).data!!.jsonObject

        assertEquals("false", data["accessibilityConnected"]!!.jsonPrimitive.content)
    }

    @Test
    fun `device commands report SERVICE_UNAVAILABLE when accessibility is off`() = runTest {
        val offline = CommandDispatcher({ null }, appVersion = "2.0.0")
        val response = offline.dispatch(request("tap", """{"x":1,"y":2}"""))

        assertFalse(response.success)
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, response.errorCode)
        // The message has to say what to do about it, not merely that it failed.
        assertTrue(response.error!!.contains("Accessibility"))
    }

    // ------------------------------------------------------------ validation

    @Test
    fun `missing required parameters are rejected by name`() = runTest {
        val response = dispatch("tap", """{"y": 100}""")

        assertFalse(response.success)
        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertTrue(response.error!!.contains("'x'"))
    }

    /**
     * Gson's `asFloat` threw on a non-numeric value, so `{"x":"500"}` surfaced as
     * "Internal error: null". It must be a description of the problem instead.
     */
    @Test
    fun `non-numeric coordinates produce a readable error, not a crash`() = runTest {
        val response = dispatch("tap", """{"x": "not a number", "y": 100}""")

        assertFalse(response.success)
        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertTrue(response.error!!.contains("must be a number"))
    }

    @Test
    fun `an unknown command is reported as such`() = runTest {
        val response = dispatch("summon_a_pony")

        assertFalse(response.success)
        assertEquals(ErrorCode.UNKNOWN_COMMAND, response.errorCode)
    }

    @Test
    fun `an unknown key lists the accepted values`() = runTest {
        val response = dispatch("press_key", """{"key":"eject"}""")

        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertTrue(response.error!!.contains("back"))
    }

    @Test
    fun `an unknown scroll direction lists the accepted values`() = runTest {
        val response = dispatch("scroll", """{"direction":"sideways"}""")

        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertTrue(response.error!!.contains("up"))
    }

    /**
     * The guard that matters most: an empty selector matches the root node, so without this
     * a caller who forgot their criteria gets a *successful* click on something arbitrary.
     */
    @Test
    fun `click_element refuses an empty selector`() = runTest {
        val response = dispatch("click_element", "{}")

        assertFalse(response.success)
        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertNull("the automator must not have been called", automator.callNamed("clickElement"))
    }

    @Test
    fun `wait_for_element refuses an empty selector`() = runTest {
        val response = dispatch("wait_for_element", "{}")

        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertNull(automator.callNamed("waitForElement"))
    }

    @Test
    fun `invalid package names are rejected before reaching the device`() = runTest {
        listOf("not a package", "../../etc/passwd", "com", "9com.example", "com..example")
            .forEach { candidate ->
                val encoded = kotlinx.serialization.json.JsonPrimitive(candidate)
                val response = dispatch("open_app", """{"package": $encoded}""")
                assertEquals("'$candidate' should be rejected", ErrorCode.INVALID_REQUEST, response.errorCode)
            }
        assertNull(automator.callNamed("openApp"))
    }

    @Test
    fun `valid package names are accepted`() = runTest {
        assertTrue(dispatch("open_app", """{"package":"com.android.chrome"}""").success)
        assertEquals("com.android.chrome", automator.callNamed("openApp")!!.args["package"])
    }

    @Test
    fun `over-long text is rejected`() = runTest {
        val response = dispatch("type_text", """{"text": "${"a".repeat(10_001)}"}""")

        assertEquals(ErrorCode.INVALID_REQUEST, response.errorCode)
        assertTrue(response.error!!.contains("limit"))
    }

    @Test
    fun `empty text is accepted for clearing a field`() = runTest {
        // Clearing an input by setting it to "" is a legitimate operation.
        assertTrue(dispatch("set_text", """{"text":""}""").success)
    }

    // ------------------------------------------------------------- clamping

    @Test
    fun `out-of-range tuning values are clamped rather than rejected`() = runTest {
        dispatch("screenshot", """{"quality": 5000, "maxDimension": 99999}""")

        val call = automator.callNamed("screenshot")!!
        assertEquals(100, call.args["quality"])
        assertEquals(4096, call.args["maxDimension"])
    }

    @Test
    fun `negative tuning values are clamped to the minimum`() = runTest {
        dispatch("get_ui_tree", """{"maxDepth": -5, "maxNodes": -1}""")

        val call = automator.callNamed("uiTree")!!
        assertEquals(0, call.args["maxDepth"])
        assertEquals(1, call.args["maxNodes"])
    }

    @Test
    fun `defaults are applied when parameters are omitted`() = runTest {
        dispatch("get_ui_tree")

        val call = automator.callNamed("uiTree")!!
        assertEquals(15, call.args["maxDepth"])
        assertEquals(3000, call.args["maxNodes"])
    }

    // -------------------------------------------------------------- failures

    @Test
    fun `an automator failure becomes a structured error response`() = runTest {
        automator.forcedFailure = FakeDeviceAutomator.failure(
            ErrorCode.NOT_FOUND,
            "No element matched",
        )

        val response = dispatch("click_element", """{"text":"Missing"}""")

        assertFalse(response.success)
        assertEquals(ErrorCode.NOT_FOUND, response.errorCode)
        assertEquals("No element matched", response.error)
    }

    /**
     * Every command carries a deadline, so a call that hangs inside a platform API fails
     * cleanly rather than leaving the client waiting on its own much longer timeout.
     */
    @Test
    fun `a command that overruns its deadline times out`() = runTest {
        automator.artificialDelayMillis = 60_000

        val response = dispatch("tap", """{"x":1,"y":2}""")

        assertFalse(response.success)
        assertEquals(ErrorCode.TIMEOUT, response.errorCode)
    }

    /**
     * `wait_for_element` is the one command whose duration the caller chooses, so its
     * deadline must follow the requested timeout rather than a fixed ceiling.
     */
    @Test
    fun `wait_for_element gets a deadline derived from its own timeout`() = runTest {
        automator.artificialDelayMillis = 40_000
        automator.waitResult = com.mobilemcp.pro.automation.WaitResult(found = true, elapsedMillis = 40_000)

        // 60s requested; a fixed 20s ceiling would have cut this short.
        val response = dispatch("wait_for_element", """{"text":"Done","timeout":60000}""")

        assertTrue("a 60s wait must not be truncated by a shorter global deadline", response.success)
    }

    @Test
    fun `the response id always echoes the request id`() = runTest {
        listOf("ping", "tap", "nope").forEach { command ->
            val response = dispatcher.dispatch(
                CommandRequest(id = "trace-$command", command = command, params = JsonObject(emptyMap()))
            )
            assertEquals("trace-$command", response.id)
        }
    }
}
