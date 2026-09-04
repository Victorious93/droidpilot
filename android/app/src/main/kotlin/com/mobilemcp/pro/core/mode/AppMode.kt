package com.mobilemcp.pro.core.mode

/**
 * Which of the two operating modes the device owner has selected on this device.
 *
 * This is a **local, device-side preference**, not a security boundary — switching to
 * [DEVELOPER_AGENT] does not grant a single additional permission by itself. Every command
 * a paired client sends still goes through [com.mobilemcp.pro.core.permission.AuthorizationManager]
 * exactly as it does in [PILOT]. What the mode changes is what the app *shows* the owner
 * (an execution history panel in Developer/Agent mode) and what it reports to a connected
 * MCP client via `get_capabilities`, so the client-side agent can decide how autonomously to
 * behave. It never changes what the device will execute without authorisation.
 */
enum class AppMode(val wireName: String) {
    /** "Tell me what to do on this device, and I'll do it." Direct, one-shot commands. */
    PILOT("pilot"),

    /** "Give me an objective, and I'll plan, execute, observe, and iterate." */
    DEVELOPER_AGENT("developer_agent");

    companion object {
        val DEFAULT = PILOT

        fun fromWire(value: String?): AppMode =
            entries.firstOrNull { it.wireName == value } ?: DEFAULT
    }
}
