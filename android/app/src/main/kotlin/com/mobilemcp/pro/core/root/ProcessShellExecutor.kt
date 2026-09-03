package com.mobilemcp.pro.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Runs commands with `ProcessBuilder`.
 *
 * Three details here are load-bearing rather than incidental:
 *
 *  - **stdout and stderr are drained concurrently.** A pipe has a small kernel buffer; if
 *    only one stream is read, a command that writes enough to the other fills that buffer
 *    and blocks forever. The process never exits, and the timeout below is the only thing
 *    that saves the caller. Draining both in parallel is the fix, not a refinement.
 *  - **Output is capped while reading**, not afterwards. Reading an unbounded stream into
 *    memory and trimming later still requires holding all of it, which a root shell can
 *    trivially make fatal.
 *  - **Timeout destroys the process tree forcibly.** A command that ignores the deadline
 *    would otherwise outlive the request that started it.
 */
class ProcessShellExecutor(
    /**
     * How an elevated shell is obtained. Overridable so a device with an unusual provider
     * can be accommodated without touching this class — and so tests can substitute
     * something harmless.
     */
    private val elevatedShell: List<String> = listOf("su", "-c"),
    private val plainShell: List<String> = listOf("sh", "-c"),
) : ShellExecutor {

    override suspend fun execute(
        command: String,
        elevated: Boolean,
        timeoutMillis: Long,
    ): ShellResult = runProcess(
        argv = (if (elevated) elevatedShell else plainShell) + command,
        label = command,
        elevated = elevated,
        timeoutMillis = timeoutMillis,
    )

    override suspend fun executeArgv(
        argv: List<String>,
        elevated: Boolean,
        timeoutMillis: Long,
    ): ShellResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        // No shell is involved, so no element can be reinterpreted as syntax. This is the
        // path to use whenever any part of the command is a DroidPilot-supplied value.
        val full = if (elevated) elevatedShell.dropLast(1) + argv else argv
        return runProcess(
            argv = full,
            label = argv.joinToString(" "),
            elevated = elevated,
            timeoutMillis = timeoutMillis,
        )
    }

    private suspend fun runProcess(
        argv: List<String>,
        label: String,
        elevated: Boolean,
        timeoutMillis: Long,
    ): ShellResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()

        val process = try {
            ProcessBuilder(argv).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return@withContext ShellResult(
                command = label,
                stdout = "",
                stderr = "Could not start process: ${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                exitCode = -1,
                durationMillis = System.currentTimeMillis() - startedAt,
                status = ShellStatus.FAILED,
                elevated = elevated,
            )
        }

        try {
            val (stdout, stderr, truncated) = coroutineScope {
                val out = async { process.inputStream.readCapped() }
                val err = async { process.errorStream.readCapped() }
                val o = out.await()
                val e = err.await()
                Triple(o.first, e.first, o.second || e.second)
            }

            val finished = withContext(Dispatchers.IO) {
                process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            }

            if (!finished) {
                process.destroyForcibly()
                return@withContext ShellResult(
                    command = label,
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = -1,
                    durationMillis = System.currentTimeMillis() - startedAt,
                    status = ShellStatus.TIMEOUT,
                    elevated = elevated,
                    truncated = truncated,
                )
            }

            val exitCode = process.exitValue()
            ShellResult(
                command = label,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                durationMillis = System.currentTimeMillis() - startedAt,
                // A non-zero exit is a normal, informative outcome, not a transport
                // failure — `grep` finding nothing is exit 1 and is not an error.
                status = if (exitCode == 0) ShellStatus.SUCCESS else ShellStatus.FAILED,
                elevated = elevated,
                truncated = truncated,
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Reads a stream up to the cap, discarding the remainder. Returns the text and whether it was cut. */
    private fun InputStream.readCapped(limit: Int = ShellLimits.MAX_STREAM_CHARS): Pair<String, Boolean> {
        val builder = StringBuilder()
        var truncated = false
        bufferedReader().use { reader ->
            val buffer = CharArray(8 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (builder.length < limit) {
                    builder.append(buffer, 0, minOf(read, limit - builder.length))
                } else {
                    // Keep draining so the process is never blocked on a full pipe, but
                    // stop accumulating. Dropping the reader here would deadlock the child.
                    truncated = true
                }
            }
        }
        return builder.toString() to truncated
    }
}
