package com.mobilemcp.pro.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A bounded, in-memory history of dispatched commands, for the Developer/Agent-mode
 * execution panel.
 *
 * This intentionally holds no authority: it is a read model of commands that have already
 * been through [com.mobilemcp.pro.server.CommandDispatcher] (and, for privileged commands,
 * already through [com.mobilemcp.pro.core.permission.AuthorizationManager]). Nothing reads
 * this list to decide whether to run anything. Losing it on process death is an accepted
 * trade-off, matching [com.mobilemcp.pro.server.ServerController]'s log buffer: it is a
 * live session view, not a durable audit trail — that role stays with
 * [com.mobilemcp.pro.core.audit.AuditLogger].
 *
 * Not an `object` singleton (unlike [com.mobilemcp.pro.server.ServerController]) so it can be
 * constructed fresh in tests without resetting shared process state.
 */
class ExecutionTracker(private val maxEntries: Int = MAX_ENTRIES) {

    private val _steps = MutableStateFlow<List<ExecutionStep>>(emptyList())
    val steps: StateFlow<List<ExecutionStep>> = _steps.asStateFlow()

    fun record(step: ExecutionStep) {
        _steps.value = (_steps.value + step).takeLast(maxEntries)
    }

    fun clear() {
        _steps.value = emptyList()
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}
