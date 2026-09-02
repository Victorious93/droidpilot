package com.mobilemcp.pro.core

/**
 * Structured outcome for every operation that can fail.
 *
 * The Android surface of DroidPilot is full of calls that fail for reasons the caller
 * genuinely needs to distinguish — a gesture the system refused, a permission that was
 * never granted, a capability the device does not have. Collapsing those into a thrown
 * exception (or into `null`) loses the distinction, so every fallible operation in the
 * codebase returns one of these instead.
 *
 * [ErrorCode] is the machine-readable half and is what crosses the wire; [Failure.message]
 * is the human half and is what a user sees.
 */
sealed interface OperationResult<out T> {

    data class Success<T>(val value: T) : OperationResult<T>

    data class Failure(
        val code: ErrorCode,
        val message: String,
        /** Diagnostic detail for logs. Never returned to a remote peer verbatim. */
        val detail: String? = null,
        val recoverable: Boolean = true,
    ) : OperationResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = (this as? Success)?.value

    fun <R> map(transform: (T) -> R): OperationResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun <R> flatMap(transform: (T) -> OperationResult<R>): OperationResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    companion object {
        fun <T> success(value: T): OperationResult<T> = Success(value)

        fun failure(
            code: ErrorCode,
            message: String,
            detail: String? = null,
            recoverable: Boolean = true,
        ): OperationResult<Nothing> = Failure(code, message, detail, recoverable)
    }
}

/**
 * Stable, machine-readable failure taxonomy.
 *
 * These names are part of the wire protocol: the MCP server branches on them, so treat
 * them as API. Add new codes rather than repurposing existing ones.
 */
enum class ErrorCode {
    /** Request was structurally invalid — missing or malformed parameters. */
    INVALID_REQUEST,

    /** The command name is not one this build knows about. */
    UNKNOWN_COMMAND,

    /** The operation is understood but this device or OS version cannot perform it. */
    UNSUPPORTED,

    /** A required runtime permission or special access grant is missing. */
    PERMISSION_DENIED,

    /** The Accessibility service is not currently connected. */
    SERVICE_UNAVAILABLE,

    /** The operation exceeded its deadline. */
    TIMEOUT,

    /** A UI element the command depended on could not be located. */
    NOT_FOUND,

    /** The system accepted the request but the action itself did not take effect. */
    ACTION_FAILED,

    /** Caller is not authenticated, or the secure channel is not established. */
    UNAUTHORIZED,

    /** Payload exceeded a configured safety limit. */
    LIMIT_EXCEEDED,

    /** Anything genuinely unexpected. Always accompanied by a logged stack trace. */
    INTERNAL,
}
