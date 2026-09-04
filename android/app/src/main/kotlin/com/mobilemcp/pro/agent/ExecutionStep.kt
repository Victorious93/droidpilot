package com.mobilemcp.pro.agent

/**
 * One dispatched command, recorded for the owner-facing execution history in Developer/Agent
 * mode.
 *
 * This is a record of what actually ran and what actually came back — [ExecutionTracker]
 * only ever appends entries after [CommandDispatcher][com.mobilemcp.pro.server.CommandDispatcher]
 * has a real [com.mobilemcp.pro.protocol.CommandResponse], never before or speculatively, so a
 * step can never claim a status the command has not actually reached.
 */
data class ExecutionStep(
    val requestId: String,
    val command: String,
    val status: ActionStatus,
    val summary: String,
    val error: String? = null,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
)
