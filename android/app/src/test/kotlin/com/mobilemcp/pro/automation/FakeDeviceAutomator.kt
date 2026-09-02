package com.mobilemcp.pro.automation

import com.mobilemcp.pro.core.ErrorCode
import com.mobilemcp.pro.core.OperationResult
import kotlinx.coroutines.delay

/**
 * A scriptable stand-in for a real device.
 *
 * This is what makes the dispatcher testable. `AccessibilityService` can only be exercised
 * on a running device, so without a seam here the dispatcher's validation, deadline and
 * error-mapping logic — the code most likely to contain a mistake and cheapest to test —
 * could only be checked by hand on a phone.
 *
 * Every call is recorded in [calls] so tests can assert both what came back and that the
 * dispatcher passed through the arguments it should have.
 */
class FakeDeviceAutomator : DeviceAutomator {

    data class Call(val name: String, val args: Map<String, Any?>)

    val calls = mutableListOf<Call>()

    /** When set, every operation delays this long — used to drive deadline tests. */
    var artificialDelayMillis: Long = 0

    /** When set, every operation fails with this instead of succeeding. */
    var forcedFailure: OperationResult.Failure? = null

    var screenshotResult: Screenshot = Screenshot(
        image = "ZmFrZQ==",
        format = "jpeg",
        width = 1080,
        height = 2400,
        scaled = false,
    )

    var elementsToReturn: List<UiNode> = emptyList()
    var waitResult: WaitResult = WaitResult(found = true, element = UiNode(text = "found"), elapsedMillis = 12)
    var focused: UiNode? = UiNode(text = "field", isEditable = true, isFocused = true)

    var treeResult: UiTreeResult = UiTreeResult(
        tree = UiNode(className = "android.widget.FrameLayout"),
        truncated = false,
        nodeCount = 1,
    )

    var capabilities: List<String> = listOf("accessibility", "gestures", "screenshot")

    override fun capabilityNames(): List<String> = capabilities

    private suspend fun <T> respond(name: String, args: Map<String, Any?>, value: T): OperationResult<T> {
        calls += Call(name, args)
        if (artificialDelayMillis > 0) delay(artificialDelayMillis)
        forcedFailure?.let { return it }
        return OperationResult.success(value)
    }

    override suspend fun tap(x: Float, y: Float, durationMillis: Long) =
        respond("tap", mapOf("x" to x, "y" to y, "duration" to durationMillis), "tap($x, $y)")

    override suspend fun longPress(x: Float, y: Float, durationMillis: Long) =
        respond("longPress", mapOf("x" to x, "y" to y, "duration" to durationMillis), "longPress($x, $y)")

    override suspend fun swipe(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long,
    ) = respond(
        "swipe",
        mapOf("startX" to startX, "startY" to startY, "endX" to endX, "endY" to endY, "duration" to durationMillis),
        "swipe",
    )

    override suspend fun scroll(direction: ScrollDirection, amountPx: Float) =
        respond("scroll", mapOf("direction" to direction, "amount" to amountPx), "scroll")

    override suspend fun pinch(centerX: Float?, centerY: Float?, scale: Float, durationMillis: Long) =
        respond("pinch", mapOf("x" to centerX, "y" to centerY, "scale" to scale, "duration" to durationMillis), "pinch")

    override suspend fun typeText(text: String, replace: Boolean) =
        respond("typeText", mapOf("text" to text, "replace" to replace), "typed")

    override suspend fun pressKey(key: SystemKey) =
        respond("pressKey", mapOf("key" to key), key.name.lowercase())

    override suspend fun uiTree(maxDepth: Int, maxNodes: Int) =
        respond("uiTree", mapOf("maxDepth" to maxDepth, "maxNodes" to maxNodes), treeResult)

    override suspend fun findElements(selector: ElementSelector, maxResults: Int) =
        respond("findElements", mapOf("selector" to selector, "maxResults" to maxResults), elementsToReturn)

    override suspend fun clickElement(selector: ElementSelector, longPress: Boolean) =
        respond(
            "clickElement",
            mapOf("selector" to selector, "longPress" to longPress),
            elementsToReturn.firstOrNull() ?: UiNode(text = "clicked"),
        )

    override suspend fun waitForElement(selector: ElementSelector, timeoutMillis: Long) =
        respond("waitForElement", mapOf("selector" to selector, "timeout" to timeoutMillis), waitResult)

    override suspend fun focusedElement(): OperationResult<UiNode?> =
        respond("focusedElement", emptyMap(), focused)

    override suspend fun screenshot(quality: Int, maxDimension: Int) =
        respond("screenshot", mapOf("quality" to quality, "maxDimension" to maxDimension), screenshotResult)

    override suspend fun openApp(packageName: String) =
        respond("openApp", mapOf("package" to packageName), packageName)

    override suspend fun deviceInfo() = respond(
        "deviceInfo",
        emptyMap(),
        DeviceInfo(
            manufacturer = "Fake",
            model = "Pixel Test",
            device = "fake",
            androidRelease = "15",
            sdkInt = 35,
            screenWidth = 1080,
            screenHeight = 2400,
            density = 2.75f,
            densityDpi = 440,
            appVersion = "2.0.0",
            protocolVersion = 2,
            capabilities = listOf("accessibility", "gestures", "screenshot"),
        ),
    )

    fun callNamed(name: String): Call? = calls.firstOrNull { it.name == name }

    companion object {
        fun failure(code: ErrorCode, message: String) = OperationResult.Failure(code, message)
    }
}
