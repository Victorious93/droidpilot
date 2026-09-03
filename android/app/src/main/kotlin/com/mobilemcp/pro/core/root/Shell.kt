package com.mobilemcp.pro.core.root

import kotlinx.serialization.Serializable

/** Outcome of a shell execution. Mirrors the statuses in the remote command protocol. */
enum class ShellStatus { SUCCESS, FAILED, DENIED, TIMEOUT, CANCELLED }

/**
 * A completed shell execution.
 *
 * `stdout` and `stderr` are capped by [ShellLimits]. Output from a root shell can be
 * unbounded — `logcat`, `find /`, a runaway loop — and this result travels over the network
 * and, potentially, into an AI context window. An uncapped field here is a memory problem
 * on the device, a bandwidth problem on the wire, and a cost problem in the model.
 */
@Serializable
data class ShellResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMillis: Long,
    val status: ShellStatus,
    val elevated: Boolean,
    /** True when output was cut to fit the cap, so a caller knows it is looking at a prefix. */
    val truncated: Boolean = false,
) {
    val succeeded: Boolean get() = status == ShellStatus.SUCCESS && exitCode == 0

    /**
     * A compact form for an AI context window or a log line.
     *
     * Returns the head and tail of the output rather than the middle: the command being run
     * and the error that ended it are almost always at the edges, and the repetitive bulk
     * that makes transcripts expensive is in between.
     */
    fun summarize(maxChars: Int = ShellLimits.SUMMARY_CHARS): String {
        val body = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("stderr: ").append(stderr.trim())
            }
        }
        val header = "$ $command  → exit $exitCode (${durationMillis}ms${if (elevated) ", root" else ""})"
        if (body.isEmpty()) return header

        val trimmed = if (body.length <= maxChars) {
            body
        } else {
            val half = maxChars / 2
            val omitted = body.length - (half * 2)
            body.take(half) + "\n… [$omitted characters omitted] …\n" + body.takeLast(half)
        }
        return "$header\n$trimmed"
    }
}

object ShellLimits {
    /** Per-stream cap on captured output. */
    const val MAX_STREAM_CHARS = 256 * 1024

    /** Default budget for [ShellResult.summarize]. */
    const val SUMMARY_CHARS = 2_000

    const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    const val MAX_TIMEOUT_MILLIS = 600_000L
}

/**
 * Executes shell commands, optionally elevated.
 *
 * An interface so the authorisation and result-handling logic above it can be tested
 * without running real commands, and so the elevated path has exactly one implementation
 * rather than being scattered across call sites.
 */
interface ShellExecutor {

    /**
     * Runs [command] through a shell.
     *
     * The command is passed as a single string because that is what it is — the payload the
     * owner explicitly authorised, typed at a terminal or sent by an authorised device. It
     * is not interpolated into a larger template, so there is no injection boundary to
     * protect here; the boundary is the authorisation check that precedes this call.
     *
     * Where DroidPilot builds a command from its *own* parameters, it must use [executeArgv]
     * instead, so that a value like a package name can never be read as shell syntax.
     */
    suspend fun execute(
        command: String,
        elevated: Boolean = false,
        timeoutMillis: Long = ShellLimits.DEFAULT_TIMEOUT_MILLIS,
    ): ShellResult

    /**
     * Runs an explicit argument vector, with no shell to interpret it.
     *
     * The correct choice whenever any part of the command comes from DroidPilot's own
     * parameters rather than from a command the owner authorised verbatim.
     */
    suspend fun executeArgv(
        argv: List<String>,
        elevated: Boolean = false,
        timeoutMillis: Long = ShellLimits.DEFAULT_TIMEOUT_MILLIS,
    ): ShellResult
}
