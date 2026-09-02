package com.mobilemcp.pro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mobilemcp.pro.BuildConfig
import com.mobilemcp.pro.core.Capability
import com.mobilemcp.pro.core.CapabilityReport
import com.mobilemcp.pro.core.ErrorCode
import com.mobilemcp.pro.core.OperationResult
import com.mobilemcp.pro.automation.AutomatorRegistry
import com.mobilemcp.pro.automation.Bounds
import com.mobilemcp.pro.automation.DeviceAutomator
import com.mobilemcp.pro.automation.DeviceInfo
import com.mobilemcp.pro.automation.ElementSelector
import com.mobilemcp.pro.automation.NodeAttributes
import com.mobilemcp.pro.automation.ScrollDirection
import com.mobilemcp.pro.automation.Screenshot
import com.mobilemcp.pro.automation.SystemKey
import com.mobilemcp.pro.automation.UiNode
import com.mobilemcp.pro.automation.UiTreeResult
import com.mobilemcp.pro.automation.WaitResult
import com.mobilemcp.pro.protocol.Protocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The Accessibility-backed implementation of [DeviceAutomator].
 *
 * This is the one class permitted to touch `AccessibilityNodeInfo`; everything above it
 * works with [UiNode] snapshots. Keeping live nodes from escaping matters because their
 * validity is scoped to the window generation that produced them, and a node held across
 * a screen change silently starts returning stale data.
 *
 * ### Threading
 *
 * Every operation suspends. Gesture dispatch and screen capture are callback APIs, wrapped
 * with `suspendCancellableCoroutine` so a waiting caller occupies no thread and an
 * abandoned request unwinds instead of leaking. `waitForElement` polls with `delay`, not
 * `Thread.sleep`, so a long wait costs nothing but a scheduled resumption.
 *
 * ### Node recycling
 *
 * There is none, deliberately. `AccessibilityNodeInfo.recycle()` and `obtain()` have been
 * deprecated since API 33 and are no-ops on current releases — the platform pools nodes
 * itself. The previous code carried an intricate hand-recycling scheme whose only remaining
 * effect on a modern device was the risk of using a node it had just released.
 */
class DroidPilotAccessibilityService : AccessibilityService(), DeviceAutomator {

    @Volatile
    private var capabilities: CapabilityReport = CapabilityReport.EMPTY

    override fun onServiceConnected() {
        super.onServiceConnected()
        capabilities = probeCapabilities()
        AutomatorRegistry.bind(this)
        Log.i(TAG, "Connected. Capabilities: ${capabilities.wireNames()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // DroidPilot is pull-based: the UI tree is read when a client asks for it. Reacting
        // to every event would mean waking on each frame of every animation in every app,
        // for data nobody requested. The service config still subscribes broadly because
        // `flagRetrieveInteractiveWindows` requires it.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility feedback interrupted")
    }

    override fun onDestroy() {
        AutomatorRegistry.unbind(this)
        capabilities = CapabilityReport.EMPTY
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
    }

    fun capabilityReport(): CapabilityReport = capabilities

    override fun capabilityNames(): List<String> = capabilities.wireNames()

    // ---------------------------------------------------------------- gestures

    override suspend fun tap(x: Float, y: Float, durationMillis: Long): OperationResult<String> =
        strokeGesture(
            label = "tap($x, $y)",
            durationMillis = durationMillis,
        ) { moveTo(x, y) }

    override suspend fun longPress(x: Float, y: Float, durationMillis: Long): OperationResult<String> =
        strokeGesture(
            label = "longPress($x, $y)",
            durationMillis = durationMillis,
        ) { moveTo(x, y) }

    override suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMillis: Long,
    ): OperationResult<String> = strokeGesture(
        label = "swipe($startX,$startY -> $endX,$endY)",
        durationMillis = durationMillis,
    ) {
        moveTo(startX, startY)
        lineTo(endX, endY)
    }

    override suspend fun scroll(direction: ScrollDirection, amountPx: Float): OperationResult<String> {
        val (width, height) = screenSize()
        val centerX = width / 2f
        val centerY = height / 2f
        val half = amountPx / 2f

        // The gesture is the inverse of the requested direction: scrolling content *down*
        // means dragging the finger *up*.
        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.DOWN -> listOf(centerX, centerY + half, centerX, centerY - half)
            ScrollDirection.UP -> listOf(centerX, centerY - half, centerX, centerY + half)
            ScrollDirection.RIGHT -> listOf(centerX + half, centerY, centerX - half, centerY)
            ScrollDirection.LEFT -> listOf(centerX - half, centerY, centerX + half, centerY)
        }

        return strokeGesture(
            label = "scroll(${direction.name.lowercase()})",
            durationMillis = SCROLL_DURATION_MILLIS,
        ) {
            moveTo(startX.coerceIn(0f, width - 1f), startY.coerceIn(0f, height - 1f))
            lineTo(endX.coerceIn(0f, width - 1f), endY.coerceIn(0f, height - 1f))
        }
    }

    override suspend fun pinch(
        centerX: Float?, centerY: Float?,
        scale: Float, durationMillis: Long,
    ): OperationResult<String> {
        if (Capability.GESTURES !in capabilities) return gesturesUnavailable()

        val (width, height) = screenSize()
        val cx = centerX ?: (width / 2f)
        val cy = centerY ?: (height / 2f)
        val startSpan = PINCH_START_SPAN_PX
        val endSpan = (startSpan * scale).coerceIn(MIN_PINCH_SPAN_PX, width / 2f)

        val left = Path().apply {
            moveTo((cx - startSpan).coerceAtLeast(0f), cy)
            lineTo((cx - endSpan).coerceAtLeast(0f), cy)
        }
        val right = Path().apply {
            moveTo((cx + startSpan).coerceAtMost(width - 1f), cy)
            lineTo((cx + endSpan).coerceAtMost(width - 1f), cy)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0, durationMillis))
            .addStroke(GestureDescription.StrokeDescription(right, 0, durationMillis))
            .build()

        return awaitGesture(gesture, "pinch(scale=$scale)")
    }

    private suspend fun strokeGesture(
        label: String,
        durationMillis: Long,
        buildPath: Path.() -> Unit,
    ): OperationResult<String> {
        if (Capability.GESTURES !in capabilities) return gesturesUnavailable()

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply(buildPath), 0, durationMillis))
            .build()
        return awaitGesture(gesture, label)
    }

    /**
     * Dispatches [gesture] and suspends until the system reports completion.
     *
     * `dispatchGesture` reports asynchronously; the previous implementation blocked a
     * worker thread on a `CountDownLatch` with a fixed five-second timeout, which both
     * consumed a scarce pool thread and silently reported failure for any legitimately
     * longer gesture. Here the continuation carries the result and the caller's own
     * deadline governs, so a cancelled request stops waiting immediately.
     */
    private suspend fun awaitGesture(
        gesture: GestureDescription,
        label: String,
    ): OperationResult<String> = suspendCancellableCoroutine { continuation ->
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(OperationResult.success(label))
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) {
                    continuation.resume(
                        OperationResult.failure(
                            ErrorCode.ACTION_FAILED,
                            "Gesture was cancelled by the system",
                            detail = label,
                        )
                    )
                }
            }
        }

        val accepted = dispatchGesture(gesture, callback, null)
        if (!accepted && continuation.isActive) {
            // The system refused to queue the gesture at all — usually another gesture is
            // in flight, or the display is off.
            continuation.resume(
                OperationResult.failure(
                    ErrorCode.ACTION_FAILED,
                    "System refused to dispatch the gesture (display off, or another gesture in progress)",
                    detail = label,
                )
            )
        }
    }

    private fun gesturesUnavailable(): OperationResult<Nothing> = OperationResult.failure(
        ErrorCode.UNSUPPORTED,
        capabilities.reasonFor(Capability.GESTURES),
        recoverable = false,
    )

    // ------------------------------------------------------------------- text

    override suspend fun typeText(text: String, replace: Boolean): OperationResult<String> {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return OperationResult.failure(
                ErrorCode.NOT_FOUND,
                "No input field currently has focus. Click the field first, then type.",
            )

        if (!focused.isEditable) {
            return OperationResult.failure(
                ErrorCode.ACTION_FAILED,
                "The focused element is not a text field",
                detail = focused.className?.toString(),
            )
        }

        val value = if (replace) text else (focused.text?.toString() ?: "") + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }

        return if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            // The value is deliberately not echoed back or logged: this field may hold a
            // password, and the response travels over the network and into an AI transcript.
            OperationResult.success(if (replace) "text replaced (${value.length} chars)" else "text appended (${text.length} chars)")
        } else {
            OperationResult.failure(
                ErrorCode.ACTION_FAILED,
                "The field rejected the text. Some apps block programmatic input.",
            )
        }
    }

    // -------------------------------------------------------------------- keys

    override suspend fun pressKey(key: SystemKey): OperationResult<String> {
        val action = when (key) {
            SystemKey.BACK -> GLOBAL_ACTION_BACK
            SystemKey.HOME -> GLOBAL_ACTION_HOME
            SystemKey.RECENTS -> GLOBAL_ACTION_RECENTS
            SystemKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            SystemKey.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            SystemKey.POWER_DIALOG -> GLOBAL_ACTION_POWER_DIALOG
            SystemKey.SPLIT_SCREEN -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            SystemKey.LOCK_SCREEN -> {
                if (Capability.LOCK_SCREEN !in capabilities) {
                    return OperationResult.failure(
                        ErrorCode.UNSUPPORTED,
                        capabilities.reasonFor(Capability.LOCK_SCREEN),
                        recoverable = false,
                    )
                }
                GLOBAL_ACTION_LOCK_SCREEN
            }
            SystemKey.TAKE_SCREENSHOT -> GLOBAL_ACTION_TAKE_SCREENSHOT
        }

        return if (performGlobalAction(action)) {
            OperationResult.success(key.name.lowercase())
        } else {
            OperationResult.failure(
                ErrorCode.ACTION_FAILED,
                "The system rejected the '${key.name.lowercase()}' action",
            )
        }
    }

    // ---------------------------------------------------------------- ui tree

    override suspend fun uiTree(maxDepth: Int, maxNodes: Int): OperationResult<UiTreeResult> {
        val root = rootInActiveWindow ?: return noActiveWindow()
        val budget = NodeBudget(maxNodes)
        val tree = snapshot(root, depth = 0, maxDepth = maxDepth, budget = budget)
        return OperationResult.success(
            UiTreeResult(tree = tree, truncated = budget.exhausted, nodeCount = budget.used)
        )
    }

    override suspend fun findElements(
        selector: ElementSelector,
        maxResults: Int,
    ): OperationResult<List<UiNode>> {
        val root = rootInActiveWindow ?: return noActiveWindow()
        val matches = ArrayList<UiNode>(maxResults)
        collectMatches(root, selector, matches, maxResults)
        return OperationResult.success(matches)
    }

    override suspend fun clickElement(
        selector: ElementSelector,
        longPress: Boolean,
    ): OperationResult<UiNode> {
        val root = rootInActiveWindow ?: return noActiveWindow()

        val match = firstMatch(root, selector)
            ?: return OperationResult.failure(
                ErrorCode.NOT_FOUND,
                "No element matched $selector",
            )

        val snapshot = snapshot(match, depth = 0, maxDepth = 0, budget = NodeBudget(1))

        // The node carrying the label is often a TextView inside a clickable container, so
        // walk up to the nearest actionable ancestor before giving up.
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var candidate: AccessibilityNodeInfo? = match
        var hops = 0
        while (candidate != null && hops < MAX_ANCESTOR_HOPS) {
            val actionable = if (longPress) candidate.isLongClickable else candidate.isClickable
            if (actionable && candidate.isEnabled) {
                return if (candidate.performAction(action)) {
                    OperationResult.success(snapshot)
                } else {
                    OperationResult.failure(
                        ErrorCode.ACTION_FAILED,
                        "Found the element but the ${if (longPress) "long-click" else "click"} was rejected",
                    )
                }
            }
            candidate = candidate.parent
            hops++
        }

        return OperationResult.failure(
            ErrorCode.ACTION_FAILED,
            "Matched an element, but neither it nor its ancestors are ${if (longPress) "long-clickable" else "clickable"}. " +
                "Try tapping its centre coordinates instead.",
            detail = snapshot.bounds?.let { "bounds=$it" },
        )
    }

    override suspend fun waitForElement(
        selector: ElementSelector,
        timeoutMillis: Long,
    ): OperationResult<WaitResult> {
        val startedAt = System.currentTimeMillis()
        var elapsed = 0L

        while (elapsed <= timeoutMillis) {
            rootInActiveWindow?.let { root ->
                firstMatch(root, selector)?.let { match ->
                    return OperationResult.success(
                        WaitResult(
                            found = true,
                            element = snapshot(match, depth = 0, maxDepth = 0, budget = NodeBudget(1)),
                            elapsedMillis = System.currentTimeMillis() - startedAt,
                        )
                    )
                }
            }
            // `delay` rather than `Thread.sleep`: this frees the thread for the duration,
            // which is what stops concurrent waits from starving the dispatcher.
            delay(POLL_INTERVAL_MILLIS)
            elapsed = System.currentTimeMillis() - startedAt
        }

        // A timeout is a legitimate answer to "did this appear?", not a transport failure,
        // so it succeeds with found=false and lets the caller decide.
        return OperationResult.success(
            WaitResult(found = false, elapsedMillis = System.currentTimeMillis() - startedAt)
        )
    }

    override suspend fun focusedElement(): OperationResult<UiNode?> {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return OperationResult.success(null)
        return OperationResult.success(snapshot(focused, depth = 0, maxDepth = 0, budget = NodeBudget(1)))
    }

    private fun <T> noActiveWindow(): OperationResult<T> = OperationResult.failure(
        ErrorCode.SERVICE_UNAVAILABLE,
        "No active window. The screen may be off, or showing a surface DroidPilot cannot read (a secure window, or the lock screen).",
    )

    // ------------------------------------------------------------- screenshot

    override suspend fun screenshot(quality: Int, maxDimension: Int): OperationResult<Screenshot> {
        if (Capability.SCREENSHOT !in capabilities) {
            return OperationResult.failure(
                ErrorCode.UNSUPPORTED,
                capabilities.reasonFor(Capability.SCREENSHOT),
                recoverable = false,
            )
        }

        val captured = awaitScreenshot()
        val bitmap = when (captured) {
            is OperationResult.Failure -> return captured
            is OperationResult.Success -> captured.value
        }

        return try {
            val scaled = downscale(bitmap, maxDimension)
            val stream = ByteArrayOutputStream(INITIAL_JPEG_BUFFER_BYTES)
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()

            if (bytes.size > MAX_SCREENSHOT_BYTES) {
                // Guard against a single frame monopolising memory on both ends. Base64
                // inflates by a third again, and the whole thing is buffered as one string.
                OperationResult.failure(
                    ErrorCode.LIMIT_EXCEEDED,
                    "Screenshot is ${bytes.size / 1024} KB, over the ${MAX_SCREENSHOT_BYTES / 1024} KB limit. " +
                        "Lower `quality` or `maxDimension`.",
                )
            } else {
                OperationResult.success(
                    Screenshot(
                        image = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        format = "jpeg",
                        width = scaled.width,
                        height = scaled.height,
                        scaled = scaled !== bitmap,
                    )
                )
            }.also {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun awaitScreenshot(): OperationResult<Bitmap> =
        suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = try {
                            // The hardware buffer is only valid until it is closed, so the
                            // pixels are copied into a software bitmap before releasing it.
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.let { hardware ->
                                    hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
                                }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read screenshot buffer", e)
                            null
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }

                        if (!continuation.isActive) {
                            bitmap?.recycle()
                            return
                        }
                        continuation.resume(
                            bitmap?.let { OperationResult.success(it) }
                                ?: OperationResult.failure(
                                    ErrorCode.INTERNAL,
                                    "Screen was captured but the image could not be decoded",
                                )
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(screenshotError(errorCode))
                        }
                    }
                },
            )
        }

    /**
     * Maps the platform's screenshot error codes to something actionable.
     *
     * `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` in particular is a rate limit, not a
     * fault: the caller simply asked twice within the platform's minimum interval and
     * should retry. Reporting every code as "Screenshot failed", as the previous version
     * did, made a one-second wait look like a broken capability.
     */
    private fun screenshotError(errorCode: Int): OperationResult<Bitmap> = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> OperationResult.failure(
            ErrorCode.LIMIT_EXCEEDED,
            "Screenshots are rate-limited by Android; retry in about a second.",
        )
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> OperationResult.failure(
            ErrorCode.PERMISSION_DENIED,
            "The Accessibility service is not permitted to capture the screen.",
            recoverable = false,
        )
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> OperationResult.failure(
            ErrorCode.UNSUPPORTED,
            "The default display is not capturable on this device.",
            recoverable = false,
        )
        else -> OperationResult.failure(
            ErrorCode.INTERNAL,
            "Android could not capture the screen (error $errorCode). A secure window may be showing.",
        )
    }

    private fun downscale(source: Bitmap, maxDimension: Int): Bitmap {
        val longestEdge = maxOf(source.width, source.height)
        if (maxDimension <= 0 || longestEdge <= maxDimension) return source

        val ratio = maxDimension.toFloat() / longestEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            /* filter = */ true,
        )
    }

    // -------------------------------------------------------------------- apps

    override suspend fun openApp(packageName: String): OperationResult<String> {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return OperationResult.failure(
                ErrorCode.NOT_FOUND,
                "No launchable activity for '$packageName'. The app may not be installed, " +
                    "or it may have no launcher icon.",
            )

        return try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OperationResult.success(packageName)
        } catch (e: SecurityException) {
            OperationResult.failure(
                ErrorCode.PERMISSION_DENIED,
                "Not allowed to launch '$packageName'",
                detail = e.message,
            )
        } catch (e: android.content.ActivityNotFoundException) {
            OperationResult.failure(
                ErrorCode.NOT_FOUND,
                "Launch activity for '$packageName' disappeared between lookup and start",
                detail = e.message,
            )
        }
    }

    override suspend fun deviceInfo(): OperationResult<DeviceInfo> {
        val (width, height) = screenSize()
        val metrics = resources.displayMetrics
        return OperationResult.success(
            DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                screenWidth = width.toInt(),
                screenHeight = height.toInt(),
                density = metrics.density,
                densityDpi = metrics.densityDpi,
                appVersion = BuildConfig.VERSION_NAME,
                protocolVersion = Protocol.VERSION,
                capabilities = capabilities.wireNames(),
            )
        )
    }

    // ------------------------------------------------------------- tree walking

    /** Caps how much of the tree a single request may materialise. */
    private class NodeBudget(private val limit: Int) {
        var used: Int = 0
            private set
        var exhausted: Boolean = false
            private set

        fun take(): Boolean {
            if (used >= limit) {
                exhausted = true
                return false
            }
            used++
            return true
        }
    }

    private fun snapshot(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        budget: NodeBudget,
    ): UiNode {
        budget.take()
        val rect = Rect().also { node.getBoundsInScreen(it) }

        val children = if (depth < maxDepth && node.childCount > 0) {
            buildList {
                for (i in 0 until node.childCount) {
                    if (budget.exhausted) break
                    val child = node.getChild(i) ?: continue
                    add(snapshot(child, depth + 1, maxDepth, budget))
                }
            }.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        return UiNode(
            className = node.className?.toString(),
            // A password field's contents must never leave the device. Android already
            // withholds them from `getText()` in most cases, but relying on that is not a
            // control — this response is forwarded verbatim into an AI conversation.
            text = if (node.isPassword) null else node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isEnabled = node.isEnabled,
            isChecked = node.isChecked,
            isFocused = node.isFocused,
            isSelected = node.isSelected,
            isPassword = node.isPassword,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            packageName = node.packageName?.toString(),
            children = children,
        )
    }

    /** Adapts a live node to the attribute view that [ElementSelector] understands. */
    private class LiveNodeAttributes(private val node: AccessibilityNodeInfo) : NodeAttributes {
        override val text: String? get() = if (node.isPassword) null else node.text?.toString()
        override val contentDescription: String? get() = node.contentDescription?.toString()
        override val viewId: String? get() = node.viewIdResourceName
        override val className: String? get() = node.className?.toString()
    }

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        selector: ElementSelector,
        into: MutableList<UiNode>,
        maxResults: Int,
    ) {
        if (into.size >= maxResults) return
        if (selector.matches(LiveNodeAttributes(node))) {
            into.add(snapshot(node, depth = 0, maxDepth = 0, budget = NodeBudget(1)))
        }
        for (i in 0 until node.childCount) {
            if (into.size >= maxResults) return
            collectMatches(node.getChild(i) ?: continue, selector, into, maxResults)
        }
    }

    private fun firstMatch(
        node: AccessibilityNodeInfo,
        selector: ElementSelector,
    ): AccessibilityNodeInfo? {
        if (selector.matches(LiveNodeAttributes(node))) return node
        for (i in 0 until node.childCount) {
            firstMatch(node.getChild(i) ?: continue, selector)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------- capabilities

    /**
     * Probes what this device actually grants, once, when the service connects.
     *
     * The flags come from the live `AccessibilityServiceInfo` rather than from the XML
     * config, because the platform may withhold a requested flag — device policy and some
     * OEM builds do exactly that — and the XML would still claim it.
     */
    private fun probeCapabilities(): CapabilityReport {
        val available = mutableSetOf(Capability.ACCESSIBILITY)
        val reasons = mutableMapOf<Capability, String>()

        val info: AccessibilityServiceInfo? = serviceInfo

        if (info == null) {
            reasons[Capability.GESTURES] = "Service info unavailable; the service may still be starting"
            reasons[Capability.SCREENSHOT] = "Service info unavailable; the service may still be starting"
        } else {
            if (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0) {
                available += Capability.GESTURES
            } else {
                reasons[Capability.GESTURES] =
                    "The system did not grant gesture dispatch to this service"
            }

            if (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0) {
                available += Capability.SCREENSHOT
            } else {
                reasons[Capability.SCREENSHOT] =
                    "The system did not grant screen capture to this service"
            }
        }

        available += Capability.LOCK_SCREEN
        available += Capability.SPLIT_SCREEN

        // `<queries>` in the manifest makes every app with a launcher icon resolvable,
        // which is exactly the set `open_app` can act on.
        val launchable = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (launchable.isNotEmpty()) {
            available += Capability.APP_LAUNCH
        } else {
            reasons[Capability.APP_LAUNCH] =
                "No launchable packages are visible to DroidPilot; package visibility may be restricted"
        }

        reasons[Capability.NOTIFICATION_ACCESS] =
            "Reading notifications needs a NotificationListenerService, which this build does not include"

        return CapabilityReport(available, reasons)
    }

    private fun screenSize(): Pair<Float, Float> {
        // `WindowMetrics` is the supported path from API 30 onward. `resources.displayMetrics`
        // reports the app's own (possibly letterboxed) bounds, which is the wrong frame of
        // reference for absolute screen coordinates.
        val bounds = getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        return if (bounds != null && !bounds.isEmpty) {
            bounds.width().toFloat() to bounds.height().toFloat()
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
        }
    }

    private companion object {
        const val TAG = "DroidPilotA11y"
        const val POLL_INTERVAL_MILLIS = 250L
        const val SCROLL_DURATION_MILLIS = 400L
        const val PINCH_START_SPAN_PX = 200f
        const val MIN_PINCH_SPAN_PX = 20f
        const val MAX_ANCESTOR_HOPS = 12
        const val INITIAL_JPEG_BUFFER_BYTES = 256 * 1024
        const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024
    }
}
