package com.mobilemcp.pro.server

import com.mobilemcp.pro.agent.ActionStatus
import com.mobilemcp.pro.agent.ExecutionTracker
import com.mobilemcp.pro.automation.FakeDeviceAutomator
import com.mobilemcp.pro.core.mode.AppMode
import com.mobilemcp.pro.protocol.CommandRequest
import com.mobilemcp.pro.protocol.Protocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two additions [CommandDispatcher] carries for Developer/Agent mode:
 * reporting the current mode from `get_capabilities`, and recording dispatched commands
 * into an [ExecutionTracker] for the owner-facing execution history.
 *
 * Neither of these affects authorisation or execution — that is still entirely
 * [com.mobilemcp.pro.server.PrivilegedCommandGateway]'s job — so these tests only assert on
 * what gets reported and recorded, not on what is allowed to run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandDispatcherExecutionTrackingTest {

    private val automator = FakeDeviceAutomator()

    private fun request(command: String, params: String = "{}") = CommandRequest(
        id = "req-1",
        command = command,
        params = Protocol.json.parseToJsonElement(params).jsonObject,
    )

    @Test
    fun `get_capabilities reports the configured mode`() = runTest {
        val dispatcher = CommandDispatcher(
            { automator },
            appVersion = "test",
            currentMode = { AppMode.DEVELOPER_AGENT },
        )

        val data = dispatcher.dispatch(request("get_capabilities")).data!!.jsonObject

        assertEquals("developer_agent", data["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_capabilities defaults to pilot when no mode is configured`() = runTest {
        val dispatcher = CommandDispatcher({ automator }, appVersion = "test")

        val data = dispatcher.dispatch(request("get_capabilities")).data!!.jsonObject

        assertEquals("pilot", data["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a successful command is recorded as SUCCESS`() = runTest {
        val tracker = ExecutionTracker()
        val dispatcher = CommandDispatcher({ automator }, appVersion = "test", tracker = tracker)

        dispatcher.dispatch(request("tap", """{"x":1,"y":2}"""))

        val recorded = tracker.steps.value.single()
        assertEquals("tap", recorded.command)
        assertEquals(ActionStatus.SUCCESS, recorded.status)
    }

    @Test
    fun `a failed command is recorded with the mapped status and error message`() = runTest {
        val tracker = ExecutionTracker()
        val dispatcher = CommandDispatcher({ null }, appVersion = "test", tracker = tracker)

        dispatcher.dispatch(request("tap", """{"x":1,"y":2}"""))

        val recorded = tracker.steps.value.single()
        assertEquals(ActionStatus.BLOCKED, recorded.status)
        assertTrue(recorded.error!!.contains("Accessibility"))
    }

    @Test
    fun `ping and get_capabilities are not recorded`() = runTest {
        val tracker = ExecutionTracker()
        val dispatcher = CommandDispatcher({ automator }, appVersion = "test", tracker = tracker)

        dispatcher.dispatch(request("ping"))
        dispatcher.dispatch(request("get_capabilities"))

        assertTrue(tracker.steps.value.isEmpty())
    }

    @Test
    fun `nothing is recorded when no tracker is configured`() = runTest {
        val dispatcher = CommandDispatcher({ automator }, appVersion = "test")

        // Must not throw with no tracker wired in — this is the production default.
        dispatcher.dispatch(request("tap", """{"x":1,"y":2}"""))
    }
}
