package com.mobilemcp.pro.core.mode

import android.content.Context

/**
 * Persists the owner's [AppMode] choice across restarts.
 *
 * Mirrors [com.mobilemcp.pro.core.permission.PersistentGrantStore]: storage is two lambdas
 * rather than a direct `SharedPreferences` dependency, so the read/write/default logic is
 * testable on the JVM with no Android runtime. The secondary constructor is the only part
 * that knows the bytes live in `SharedPreferences`.
 *
 * An unreadable or unrecognised stored value falls back to [AppMode.DEFAULT] ([AppMode.PILOT])
 * rather than throwing — the safe default for an operating-mode preference is the more
 * conservative, already-shipped mode.
 */
class AppModeStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) {
    constructor(context: Context) : this(
        readRaw = {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODE, null)
        },
        writeRaw = { encoded ->
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, encoded)
                .apply()
        },
    )

    fun get(): AppMode = AppMode.fromWire(readRaw())

    fun set(mode: AppMode) {
        writeRaw(mode.wireName)
    }

    private companion object {
        const val PREFS_NAME = "droidpilot_mode"
        const val KEY_MODE = "mode"
    }
}
