package com.mobilemcp.pro.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the control server is currently doing. */
sealed interface ServerState {

    data object Stopped : ServerState

    data object Starting : ServerState

    data class Running(
        val bindAddress: String,
        val port: Int,
        val connectedClients: Int,
        val secretFingerprint: String,
    ) : ServerState

    data class Failed(val reason: String) : ServerState
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String,
)

/**
 * Observable state shared between the service that owns the server and the UI that
 * displays it.
 *
 * The previous design had this backwards: `MainActivity` constructed and held the
 * WebSocket server, while the foreground service did nothing but display a notification.
 * Because an Activity is destroyed and recreated on every configuration change, **rotating
 * the screen killed the server** — and the notification carried on claiming it was running,
 * because it had never been connected to anything.
 *
 * Inverting the ownership fixes that. The service holds the server for as long as the
 * server should live; the Activity is a view that attaches, renders this state, and can be
 * destroyed at any time without consequence.
 */
object ServerController {

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    internal fun updateState(state: ServerState) {
        _state.value = state
    }

    internal fun updateClientCount(count: Int) {
        _state.update { current ->
            if (current is ServerState.Running) current.copy(connectedClients = count) else current
        }
    }

    /**
     * Appends a log line, discarding the oldest once the buffer is full.
     *
     * The bound matters: log lines arrive per command, and the previous implementation
     * appended into a `TextView` and then trimmed by reading the entire buffer back out as
     * a `String` on every single line — quadratic work on the main thread, during exactly
     * the traffic burst that made it expensive.
     */
    internal fun log(level: LogLevel, message: String) {
        _logs.update { existing ->
            val appended = existing + LogEntry(System.currentTimeMillis(), level, message)
            if (appended.size > MAX_LOG_ENTRIES) {
                appended.subList(appended.size - MAX_LOG_ENTRIES, appended.size)
            } else {
                appended
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    const val MAX_LOG_ENTRIES = 300
}
