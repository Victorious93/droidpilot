package com.mobilemcp.pro.automation

import com.mobilemcp.pro.core.OperationResult
import kotlinx.serialization.Serializable

/**
 * Everything DroidPilot can ask of a device, expressed without reference to Android.
 *
 * The boundary exists so the command dispatcher — which does parameter validation,
 * timeouts and error mapping, and is the part with the most branching — can be tested
 * against a fake. Instrumented tests on a physical device are the only way to exercise a
 * real `AccessibilityService`, and a test suite that requires a phone plugged in is a test
 * suite that stops being run.
 *
 * Every method suspends and returns [OperationResult]. Nothing here throws for an expected
 * failure, and nothing blocks a thread: the previous implementation parked worker threads
 * on `CountDownLatch.await` and `Thread.sleep`, which let four concurrent `wait_for_element`
 * calls exhaust a fixed pool of four and wedge the whole server.
 */
interface DeviceAutomator {

    /**
     * Wire names of the capabilities this device actually grants.
     *
     * Part of the interface so the control server can advertise capabilities in its
     * handshake without reaching through to a concrete `AccessibilityService`. That
     * downcast was a layering violation, and it also made the server impossible to test
     * off-device, since the test would have had to load an Android service class.
     */
    fun capabilityNames(): List<String>

    suspend fun tap(x: Float, y: Float, durationMillis: Long): OperationResult<String>

    suspend fun longPress(x: Float, y: Float, durationMillis: Long): OperationResult<String>

    suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMillis: Long,
    ): OperationResult<String>

    suspend fun scroll(direction: ScrollDirection, amountPx: Float): OperationResult<String>

    suspend fun pinch(
        centerX: Float?, centerY: Float?,
        scale: Float, durationMillis: Long,
    ): OperationResult<String>

    suspend fun typeText(text: String, replace: Boolean): OperationResult<String>

    suspend fun pressKey(key: SystemKey): OperationResult<String>

    suspend fun uiTree(maxDepth: Int, maxNodes: Int): OperationResult<UiTreeResult>

    suspend fun findElements(selector: ElementSelector, maxResults: Int): OperationResult<List<UiNode>>

    suspend fun clickElement(selector: ElementSelector, longPress: Boolean): OperationResult<UiNode>

    suspend fun waitForElement(selector: ElementSelector, timeoutMillis: Long): OperationResult<WaitResult>

    suspend fun focusedElement(): OperationResult<UiNode?>

    suspend fun screenshot(quality: Int, maxDimension: Int): OperationResult<Screenshot>

    suspend fun openApp(packageName: String): OperationResult<String>

    suspend fun deviceInfo(): OperationResult<DeviceInfo>
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * System-level actions reachable through `performGlobalAction`.
 *
 * Modelled as an enum rather than a free string so that an unknown key is rejected at
 * parse time with a clear error, and so the set is discoverable from one place.
 */
enum class SystemKey {
    BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG, SPLIT_SCREEN, LOCK_SCREEN, TAKE_SCREENSHOT;

    companion object {
        fun fromWire(value: String): SystemKey? = when (value.lowercase()) {
            "back" -> BACK
            "home" -> HOME
            "recents", "recent" -> RECENTS
            "notifications" -> NOTIFICATIONS
            "quick_settings" -> QUICK_SETTINGS
            "power_dialog" -> POWER_DIALOG
            "split_screen" -> SPLIT_SCREEN
            "lock_screen" -> LOCK_SCREEN
            "take_screenshot" -> TAKE_SCREENSHOT
            else -> null
        }
    }
}

@Serializable
data class UiTreeResult(
    val tree: UiNode,
    /**
     * True when the walk stopped at the node budget rather than at a leaf, so the caller
     * knows the tree is partial and can re-request with a smaller depth. The old
     * implementation silently returned whatever it had.
     */
    val truncated: Boolean,
    val nodeCount: Int,
)

@Serializable
data class WaitResult(
    val found: Boolean,
    val element: UiNode? = null,
    val elapsedMillis: Long,
)

@Serializable
data class Screenshot(
    /** Base64, no wrapping. */
    val image: String,
    val format: String,
    val width: Int,
    val height: Int,
    /** True when the capture was downscaled to respect the caller's size budget. */
    val scaled: Boolean,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val densityDpi: Int,
    val appVersion: String,
    val protocolVersion: Int,
    /** Names of the optional capabilities this device actually grants. See CapabilityReport. */
    val capabilities: List<String>,
)
