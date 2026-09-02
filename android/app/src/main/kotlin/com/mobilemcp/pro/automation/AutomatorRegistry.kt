package com.mobilemcp.pro.automation

/**
 * The single hand-off point between the Accessibility service and the command server.
 *
 * This is process-global mutable state, which is normally worth avoiding — but Android
 * constructs an `AccessibilityService` itself and offers no binder, no factory and no
 * injection point, so some global rendezvous is unavoidable. Confining it to one small
 * class with a typed interface is the useful part: the server depends on
 * [DeviceAutomator], not on a service class, so it can be handed a fake in tests.
 *
 * The field is `@Volatile`. The previous implementation exposed a plain mutable static
 * that the service wrote from the main thread and the WebSocket worker threads read
 * without synchronisation — a data race under the Java memory model, where a reader could
 * observe a stale `null` after connection or a stale reference after teardown.
 *
 * [unbind] is identity-checked so that a service instance being destroyed after a
 * replacement has already registered cannot null out the live one.
 */
object AutomatorRegistry {

    @Volatile
    private var current: DeviceAutomator? = null

    fun bind(automator: DeviceAutomator) {
        current = automator
    }

    fun unbind(automator: DeviceAutomator) {
        if (current === automator) current = null
    }

    /** Returns the connected automator, or `null` when the Accessibility service is off. */
    fun get(): DeviceAutomator? = current

    val isConnected: Boolean get() = current != null
}
