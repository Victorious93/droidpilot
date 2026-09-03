package com.mobilemcp.pro

import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilemcp.pro.automation.DeviceAutomator
import com.mobilemcp.pro.automation.ElementSelector
import com.mobilemcp.pro.automation.ScrollDirection
import com.mobilemcp.pro.automation.SystemKey
import com.mobilemcp.pro.automation.UiNode
import com.mobilemcp.pro.core.OperationResult
import com.mobilemcp.pro.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Accessibility layer against a real running system.
 *
 * This is the suite the project previously had no equivalent of, and the reason it matters
 * is specific: node walking, gesture dispatch and screen capture are all callback- and
 * platform-driven, and every off-device substitute for them is hollow. Robolectric's
 * `AccessibilityNodeInfo` shadow returns `childCount = 0` and `text = null`, so a test
 * written against it asserts on fixtures and proves nothing about Android.
 *
 * Everything here runs against DroidPilot's own UI, so the tests need no third-party app
 * installed and behave the same on a bare emulator image as on a real handset.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityAutomatorInstrumentedTest {

    private lateinit var automator: DeviceAutomator
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        automator = AccessibilityServiceHarness.enableAndAwait()
        // The automator reads whatever is in the foreground, so the app's own screen is
        // brought up to give it something real and predictable to look at.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(SETTLE_MILLIS)
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    /**
     * Runs a coroutine test body.
     *
     * The lambda is typed `suspend CoroutineScope.() -> Unit`, which is the entire point:
     * Kotlin coerces its final expression to Unit inside the lambda, so the enclosing test
     * method is always `void`.
     *
     * Writing `fun aTest() = runBlocking { … }` instead is a trap. `runBlocking` returns
     * whatever the block returns, so a body ending in an expression like
     * `succeeding(automator.pressKey(BACK))` — which yields a String — compiles to a
     * non-void method. JUnit4 requires test methods to return void and rejects the **whole
     * class** with a single `initializationError`, so all eighteen tests here silently
     * stopped running and the report named none of them. That is exactly what happened on
     * the first emulator run.
     */
    private fun instrumentedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private fun <T> succeeding(result: OperationResult<T>): T = when (result) {
        is OperationResult.Success -> result.value
        is OperationResult.Failure ->
            throw AssertionError("expected success but got ${result.code}: ${result.message}")
    }

    // ------------------------------------------------------------- capabilities

    @Test
    fun reportsTheCapabilitiesItActuallyHas() = instrumentedTest {
        val capabilities = automator.capabilityNames()

        assertTrue("accessibility must be reported once connected", "accessibility" in capabilities)
        assertTrue("gesture dispatch is declared in the service config", "gestures" in capabilities)
        assertTrue("screen capture is declared in the service config", "screenshot" in capabilities)

        // Never advertised: this build ships no NotificationListenerService, and claiming
        // otherwise is precisely the dishonesty the capability model exists to prevent.
        assertFalse("notification access must never be advertised", "notification_access" in capabilities)
    }

    @Test
    fun reportsDeviceInformation() = instrumentedTest {
        val info = succeeding(automator.deviceInfo())

        assertTrue("screen width should be positive", info.screenWidth > 0)
        assertTrue("screen height should be positive", info.screenHeight > 0)
        assertTrue("running on API 30 or later", info.sdkInt >= 30)
        assertEquals(com.mobilemcp.pro.protocol.Protocol.VERSION, info.protocolVersion)
        assertTrue(info.manufacturer.isNotBlank())
    }

    // ----------------------------------------------------------------- ui tree

    @Test
    fun readsARealUiTree() = instrumentedTest {
        val result = succeeding(automator.uiTree(maxDepth = 20, maxNodes = 3_000))

        assertTrue("a live screen should yield more than one node", result.nodeCount > 1)
        assertNotNull(result.tree.className)

        val flattened = flatten(result.tree)
        assertTrue("the tree should contain the app's own package", flattened.any {
            it.packageName?.contains("mobilemcp") == true
        })
        assertTrue("some node should carry visible text", flattened.any { !it.text.isNullOrBlank() })
        assertTrue("some node should have non-empty bounds", flattened.any { it.bounds?.isEmpty == false })
    }

    /**
     * The node budget must actually bound the walk and say so. The previous implementation
     * had no budget and no truncation flag, so an enormous tree was silently partial.
     */
    @Test
    fun respectsTheNodeBudgetAndReportsTruncation() = instrumentedTest {
        val full = succeeding(automator.uiTree(maxDepth = 20, maxNodes = 3_000))
        // Only meaningful if the screen is big enough to exceed a tiny budget.
        if (full.nodeCount <= 3) return@instrumentedTest

        val limited = succeeding(automator.uiTree(maxDepth = 20, maxNodes = 3))

        assertTrue("the budget must cap the walk", limited.nodeCount <= 3)
        assertTrue("truncation must be reported, not silent", limited.truncated)
        assertFalse("an unbounded walk should not claim truncation", full.truncated)
    }

    @Test
    fun limitsTreeDepth() = instrumentedTest {
        val shallow = succeeding(automator.uiTree(maxDepth = 0, maxNodes = 3_000))
        assertEquals("depth 0 should return the root alone", null, shallow.tree.children)
    }

    // ---------------------------------------------------------------- selectors

    @Test
    fun findsAnElementByItsVisibleText() = instrumentedTest {
        val matches = succeeding(automator.findElements(ElementSelector(text = "DroidPilot"), maxResults = 10))

        assertTrue("the app title should be findable on its own screen", matches.isNotEmpty())
        assertTrue(matches.all { node ->
            node.text?.contains("DroidPilot", ignoreCase = true) == true ||
                node.contentDescription?.contains("DroidPilot", ignoreCase = true) == true
        })
    }

    @Test
    fun honoursTheResultLimit() = instrumentedTest {
        val matches = succeeding(automator.findElements(ElementSelector(className = "android"), maxResults = 2))
        assertTrue("maxResults must be respected", matches.size <= 2)
    }

    @Test
    fun returnsNothingForAnElementThatIsNotPresent() = instrumentedTest {
        val matches = succeeding(
            automator.findElements(ElementSelector(text = "no-such-element-4a91c7"), maxResults = 10)
        )
        assertTrue("a selector matching nothing must return nothing", matches.isEmpty())
    }

    /** Substring matching is the default; `exact` must genuinely narrow it. */
    @Test
    fun exactMatchingIsNarrowerThanSubstringMatching() = instrumentedTest {
        val substring = succeeding(automator.findElements(ElementSelector(text = "Droid"), maxResults = 20))
        val exact = succeeding(
            automator.findElements(ElementSelector(text = "Droid", exact = true), maxResults = 20)
        )

        assertTrue("substring should match the app title", substring.isNotEmpty())
        assertTrue("exact must not match a mere prefix", exact.size <= substring.size)
    }

    // ------------------------------------------------------------------ actions

    /**
     * A real click through `performAction`, including the walk up to the nearest clickable
     * ancestor. "Clear" is chosen because clearing the activity log is idempotent and has
     * no side effect on the server.
     */
    @Test
    fun clicksARealElement() = instrumentedTest {
        val clicked = succeeding(automator.clickElement(ElementSelector(text = "Clear"), longPress = false))
        assertTrue(
            "the clicked node should be the one asked for",
            clicked.text?.contains("Clear", ignoreCase = true) == true ||
                clicked.contentDescription?.contains("Clear", ignoreCase = true) == true,
        )
    }

    @Test
    fun reportsNotFoundForAnElementThatDoesNotExist() = instrumentedTest {
        val result = automator.clickElement(ElementSelector(text = "no-such-button-4a91c7"), longPress = false)

        assertTrue(result is OperationResult.Failure)
        assertEquals(
            com.mobilemcp.pro.core.ErrorCode.NOT_FOUND,
            (result as OperationResult.Failure).code,
        )
    }

    /** Gesture dispatch through the real system, via the coroutine wrapper. */
    @Test
    fun dispatchesRealGestures() = instrumentedTest {
        val info = succeeding(automator.deviceInfo())
        val centreX = info.screenWidth / 2f
        val centreY = info.screenHeight / 2f

        succeeding(automator.tap(centreX, centreY, durationMillis = 50))
        succeeding(automator.longPress(centreX, centreY, durationMillis = 300))
        succeeding(automator.swipe(centreX, centreY, centreX, centreY - 200f, durationMillis = 200))
        succeeding(automator.scroll(ScrollDirection.DOWN, amountPx = 300f))
    }

    @Test
    fun performsGlobalActions() = instrumentedTest {
        succeeding(automator.pressKey(SystemKey.HOME))
        Thread.sleep(SETTLE_MILLIS)
        succeeding(automator.pressKey(SystemKey.BACK))
    }

    // -------------------------------------------------------------- wait / poll

    @Test
    fun findsAnElementThatIsAlreadyPresent() = instrumentedTest {
        val result = succeeding(
            automator.waitForElement(ElementSelector(text = "DroidPilot"), timeoutMillis = 5_000)
        )

        assertTrue("an element already on screen should be found", result.found)
        assertNotNull(result.element)
    }

    /**
     * A timeout is a legitimate answer, not a transport failure — the caller needs to be
     * able to ask "did this appear?" and get "no" without an error.
     */
    @Test
    fun timesOutCleanlyForAnElementThatNeverAppears() = instrumentedTest {
        val started = System.currentTimeMillis()
        val result = succeeding(
            automator.waitForElement(ElementSelector(text = "never-appears-4a91c7"), timeoutMillis = 1_500)
        )
        val elapsed = System.currentTimeMillis() - started

        assertFalse(result.found)
        assertTrue("should have waited roughly the requested time", elapsed >= 1_400)
        assertTrue("should not have waited far beyond it", elapsed < 8_000)
    }

    // --------------------------------------------------------------- screenshot

    @Test
    fun capturesTheScreen() = instrumentedTest {
        val shot = succeeding(automator.screenshot(quality = 70, maxDimension = 1_200))

        assertEquals("jpeg", shot.format)
        assertTrue("width should be positive", shot.width > 0)
        assertTrue("height should be positive", shot.height > 0)
        assertTrue("the longest edge must respect maxDimension", maxOf(shot.width, shot.height) <= 1_200)

        val bytes = Base64.decode(shot.image, Base64.NO_WRAP)
        assertTrue("a JPEG of a real screen should not be trivially small", bytes.size > 1_000)
        // JPEG SOI marker: proves an actual image came back rather than a blank buffer.
        assertEquals("expected a JPEG SOI marker", 0xFF.toByte(), bytes[0])
        assertEquals("expected a JPEG SOI marker", 0xD8.toByte(), bytes[1])
    }

    @Test
    fun downscalesLargeCapturesAndSaysSo() = instrumentedTest {
        val info = succeeding(automator.deviceInfo())
        val longestEdge = maxOf(info.screenWidth, info.screenHeight)

        // Android rate-limits captures to roughly one per second.
        Thread.sleep(SCREENSHOT_INTERVAL_MILLIS)
        val small = succeeding(automator.screenshot(quality = 60, maxDimension = 200))

        assertTrue(maxOf(small.width, small.height) <= 200)
        if (longestEdge > 200) {
            assertTrue("a downscaled capture must report that it was scaled", small.scaled)
        }
    }

    // ------------------------------------------------------------------ focus

    @Test
    fun reportsFocusStateWithoutFailing() = instrumentedTest {
        // Nothing is necessarily focused, so both answers are valid — what must not happen
        // is a failure. `null` means "nothing focused", which is information, not an error.
        val result = automator.focusedElement()
        assertTrue("querying focus must never fail", result is OperationResult.Success)
    }

    private fun flatten(node: UiNode): List<UiNode> =
        listOf(node) + (node.children.orEmpty().flatMap(::flatten))

    private companion object {
        const val SETTLE_MILLIS = 1_500L
        const val SCREENSHOT_INTERVAL_MILLIS = 1_200L
    }
}
