package com.mobilemcp.pro.agent

import com.mobilemcp.pro.core.ErrorCode

/**
 * The standardised outcome of one dispatched command, independent of which command it was.
 *
 * This is the classification an execution-history UI or a Developer/Agent-mode client needs
 * to decide what to do next — retry, ask the owner, or stop — which a bare [ErrorCode] does
 * not distinguish cleanly (e.g. [ErrorCode.TIMEOUT] is worth retrying, [ErrorCode.NOT_FOUND]
 * usually is not).
 */
enum class ActionStatus {
    SUCCESS,
    FAILED,
    BLOCKED,
    REQUIRES_PERMISSION,
    REQUIRES_USER,
    RETRYABLE;

    companion object {
        /**
         * Maps a dispatcher failure to the coarser status an execution history or a
         * Developer/Agent-mode client reasons over.
         *
         * This mapping is deliberately conservative: a code this function does not
         * recognise as retryable or permission-shaped falls back to [FAILED] rather than a
         * more optimistic guess, because a caller that over-trusts "retryable" can loop
         * forever on a command that will never succeed.
         */
        fun from(code: ErrorCode): ActionStatus = when (code) {
            ErrorCode.PERMISSION_DENIED, ErrorCode.UNAUTHORIZED -> REQUIRES_PERMISSION
            ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.UNSUPPORTED -> BLOCKED
            ErrorCode.TIMEOUT -> RETRYABLE
            ErrorCode.NOT_FOUND -> RETRYABLE
            ErrorCode.INVALID_REQUEST, ErrorCode.UNKNOWN_COMMAND,
            ErrorCode.ACTION_FAILED, ErrorCode.LIMIT_EXCEEDED, ErrorCode.INTERNAL -> FAILED
        }
    }
}
