package com.mobilemcp.pro.core

/**
 * Optional device abilities that DroidPilot detects rather than assumes.
 *
 * Android grants an app a shifting subset of what it asks for: an OS version withholds an
 * API, a user declines a permission, an OEM disables a global action, a work profile
 * blocks screenshots. Code that assumes an ability is present fails at the moment of use,
 * usually as an opaque "action failed" three layers away from the cause.
 *
 * So each ability is named here, probed at runtime, and reported to the connecting client
 * in the handshake. A caller can then see that screenshots are unavailable *and why*,
 * instead of watching every `screenshot` call fail.
 *
 * The wire names are API. Add entries rather than renaming them.
 */
enum class Capability(val wireName: String, val description: String) {

    ACCESSIBILITY(
        "accessibility",
        "Accessibility service is connected and can read the UI tree",
    ),

    GESTURES(
        "gestures",
        "Service may dispatch taps, swipes and other gestures",
    ),

    SCREENSHOT(
        "screenshot",
        "Service may capture the screen without a MediaProjection prompt",
    ),

    LOCK_SCREEN(
        "lock_screen",
        "Device can be locked via a global action (API 28+)",
    ),

    SPLIT_SCREEN(
        "split_screen",
        "Split-screen toggle is available",
    ),

    APP_LAUNCH(
        "app_launch",
        "Other applications can be resolved and launched by package name",
    ),

    POST_NOTIFICATIONS(
        "post_notifications",
        "App may show its own foreground-service notification (Android 13+ runtime grant)",
    ),

    /**
     * Reading the notification shade requires a `NotificationListenerService` with the
     * user's explicit grant. DroidPilot does not currently ship one, so this is always
     * reported unavailable with that reason.
     *
     * A previous build approximated it by opening the shade, sleeping, scraping every
     * `FrameLayout` it could see and pressing Back. That produced unlabelled junk, stole
     * focus from whatever the user was doing, and was not wired to any MCP tool. It has
     * been removed: a capability that is honestly reported missing is more useful than one
     * that appears to work. See ROADMAP in ARCHITECTURE.md.
     */
    NOTIFICATION_ACCESS(
        "notification_access",
        "Read posted notifications (requires NotificationListenerService — not implemented)",
    );
}

/**
 * The result of probing every [Capability], with a reason recorded for each one that is
 * unavailable. The reason is what makes this useful — "screenshot: unavailable" sends a
 * user hunting, "screenshot: service config does not set canTakeScreenshot" does not.
 */
data class CapabilityReport(
    val available: Set<Capability>,
    val reasons: Map<Capability, String>,
) {
    operator fun contains(capability: Capability): Boolean = capability in available

    fun reasonFor(capability: Capability): String =
        reasons[capability] ?: "Not available on this device"

    fun wireNames(): List<String> = available.map { it.wireName }.sorted()

    companion object {
        val EMPTY = CapabilityReport(emptySet(), emptyMap())
    }
}
