package com.mobilemcp.pro.core.permission

/**
 * The complete set of capabilities a paired device may be granted on this one.
 *
 * One enum, deliberately. A second permission system that shadows this one is how
 * "authorized here, unauthorized there" bugs happen, and in a system that can hand out a
 * root shell over the network that class of bug is not recoverable.
 *
 * The wire names are API — the remote peer sends them and audit records store them — so
 * add entries rather than renaming them.
 */
enum class RemotePermission(
    val wireName: String,
    /**
     * True for permissions that can change the device or run code on it. Privileged
     * permissions are never covered by a trust-level preset alone and always require an
     * explicit, live grant.
     */
    val privileged: Boolean,
    val description: String,
) {
    REMOTE_VIEW("remote_view", false, "Read device status, capabilities and metadata"),
    REMOTE_KNOWLEDGE("remote_knowledge", false, "Search and read the knowledge graph"),
    REMOTE_AUTOMATION("remote_automation", true, "Start and stop automations"),
    REMOTE_AI("remote_ai", true, "Ask the on-device AI subsystem questions"),
    REMOTE_FILES("remote_files", true, "Read and write files the app can reach"),
    REMOTE_SETTINGS("remote_settings", true, "Change DroidPilot's own settings"),
    REMOTE_TERMINAL("remote_terminal", true, "Open an interactive unprivileged shell session"),
    REMOTE_SHELL("remote_shell", true, "Execute unprivileged shell commands"),
    REMOTE_ROOT("remote_root", true, "Execute commands as root"),

    /**
     * A second gate that applies only when the AI subsystem — rather than a human at a
     * paired device — initiates a root command.
     *
     * It exists so "my laptop may run root commands, but the model may not" is expressible.
     * It never replaces [REMOTE_ROOT]: an AI-initiated root command needs both. See
     * [AuthorizationManager].
     */
    AI_ROOT("ai_root", true, "Allow the AI subsystem to initiate root commands");

    companion object {
        fun fromWire(value: String): RemotePermission? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
    }
}

/**
 * Convenience bundles the owner can apply when granting access to a new device.
 *
 * A preset is a shortcut for issuing several grants at once, nothing more. It is expanded
 * into individual grants at the moment it is applied and is never consulted at execution
 * time, so a preset can never widen access beyond the grants actually issued — which is
 * what stops "but they had POWER_USER" from becoming an argument for skipping a check.
 *
 * Sets are spelled out rather than composed from one another: enum entries cannot
 * reference each other during construction, and being able to read each level's exact
 * contents at a glance is worth more here than avoiding the repetition.
 */
enum class TrustLevel(val includes: Set<RemotePermission>) {

    VIEW_ONLY(
        setOf(
            RemotePermission.REMOTE_VIEW,
            RemotePermission.REMOTE_KNOWLEDGE,
        ),
    ),

    STANDARD(
        setOf(
            RemotePermission.REMOTE_VIEW,
            RemotePermission.REMOTE_KNOWLEDGE,
            RemotePermission.REMOTE_AUTOMATION,
            RemotePermission.REMOTE_AI,
        ),
    ),

    POWER_USER(
        setOf(
            RemotePermission.REMOTE_VIEW,
            RemotePermission.REMOTE_KNOWLEDGE,
            RemotePermission.REMOTE_AUTOMATION,
            RemotePermission.REMOTE_AI,
            RemotePermission.REMOTE_FILES,
            RemotePermission.REMOTE_SETTINGS,
            RemotePermission.REMOTE_TERMINAL,
            RemotePermission.REMOTE_SHELL,
        ),
    ),

    /**
     * Identical to [POWER_USER]. It deliberately does **not** include
     * [RemotePermission.REMOTE_ROOT] or [RemotePermission.AI_ROOT]: root is never conferred
     * by picking a preset from a list. It is granted by its own explicit decision, with its
     * own prompt. Pairing a device and authorising root are separate acts, and this enum is
     * where that separation would be easiest to quietly erode.
     *
     * The level exists as a label for "this device is trusted enough to be *offered* root",
     * not as a grant of it.
     */
    ROOT(
        setOf(
            RemotePermission.REMOTE_VIEW,
            RemotePermission.REMOTE_KNOWLEDGE,
            RemotePermission.REMOTE_AUTOMATION,
            RemotePermission.REMOTE_AI,
            RemotePermission.REMOTE_FILES,
            RemotePermission.REMOTE_SETTINGS,
            RemotePermission.REMOTE_TERMINAL,
            RemotePermission.REMOTE_SHELL,
        ),
    ),
}
