package com.mobilemcp.pro.core.root

import kotlinx.serialization.Serializable

/**
 * What elevated execution this device can actually do.
 *
 * Reported honestly, including the reason when it cannot. An app that claims root it does
 * not have produces failures at the moment of use, far from the cause — and on a feature
 * whose whole point is running privileged commands, that is the worst place to discover it.
 */
@Serializable
data class RootCapability(
    val available: Boolean,
    /** Reported by the provider when it identifies itself, e.g. from `su -v`. Never fabricated. */
    val providerVersion: String? = null,
    /** Populated when [available] is false, so the UI can say why rather than just "no". */
    val unavailableReason: String? = null,
) {
    companion object {
        fun unavailable(reason: String) = RootCapability(available = false, unavailableReason = reason)
    }
}

/**
 * The single entry point for elevated execution.
 *
 * One abstraction rather than `su` invocations scattered through the codebase. That is not
 * tidiness: it is the only way the authorisation check in front of root execution can be
 * guaranteed to be unskippable, because there is exactly one place that can elevate.
 *
 * Root providers are **not** assumed. There is no check for Magisk, no hard-coded path, no
 * assumption about which implementation is installed — the probe simply asks whether an
 * elevated shell can be obtained and reports what answered. A device with a provider
 * DroidPilot has never heard of works; a device with none is told so plainly.
 */
class RootManager(
    private val executor: ShellExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var cached: RootCapability? = null

    @Volatile
    private var cachedAtMillis: Long = 0

    /**
     * Probes for elevated execution, caching the answer briefly.
     *
     * Cached because the probe spawns a process and the UI asks repeatedly; expiring
     * because root can appear or disappear within a session — a user can grant or withdraw
     * DroidPilot's permission in their root manager at any time, and a stale "available"
     * would turn a clear refusal into a confusing failure.
     */
    suspend fun capability(forceRefresh: Boolean = false): RootCapability {
        val now = clock()
        cached?.let { if (!forceRefresh && now - cachedAtMillis < CAPABILITY_TTL_MILLIS) return it }

        val probed = probe()
        cached = probed
        cachedAtMillis = now
        return probed
    }

    private suspend fun probe(): RootCapability {
        val result = try {
            // `id -u` is the honest question: not "is a binary present" but "did I actually
            // become root". A provider that prompts and is denied still returns a result,
            // and it will not be uid 0.
            executor.execute("id -u", elevated = true, timeoutMillis = PROBE_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            return RootCapability.unavailable("Could not start an elevated shell (${e.javaClass.simpleName})")
        }

        return when {
            result.status == ShellStatus.TIMEOUT -> RootCapability.unavailable(
                "The root provider did not respond. It may be waiting for you to approve " +
                    "DroidPilot in its prompt — approve it and try again.",
            )

            result.stdout.trim() == "0" -> RootCapability(
                available = true,
                providerVersion = probeVersion(),
            )

            result.exitCode != 0 -> RootCapability.unavailable(
                "No root provider accepted the request. If this device is rooted, check that " +
                    "DroidPilot is permitted in your root manager.",
            )

            else -> RootCapability.unavailable(
                "An elevated shell was obtained but did not report uid 0.",
            )
        }
    }

    private suspend fun probeVersion(): String? = try {
        executor.execute("su -v", elevated = false, timeoutMillis = PROBE_TIMEOUT_MILLIS)
            .takeIf { it.succeeded }
            ?.stdout?.trim()?.takeIf { it.isNotBlank() && it.length < 128 }
    } catch (e: Exception) {
        null // Purely informational; its absence is not a failure.
    }

    /**
     * Runs [command] as root.
     *
     * **This performs no authorisation check.** Callers must have already obtained an
     * `AuthorizationDecision.Allowed` for `REMOTE_ROOT` — and, for AI-initiated commands,
     * `AI_ROOT`. Keeping the check out of here is deliberate: mixing "am I allowed" into
     * "how do I run it" makes it possible to add a call site that quietly does the second
     * without the first. The one legitimate path is `RootCommandHandler`.
     */
    suspend fun executeAsRoot(
        command: String,
        timeoutMillis: Long = ShellLimits.DEFAULT_TIMEOUT_MILLIS,
    ): ShellResult {
        val capability = capability()
        if (!capability.available) {
            return ShellResult(
                command = command,
                stdout = "",
                stderr = capability.unavailableReason ?: "Root is not available on this device",
                exitCode = -1,
                durationMillis = 0,
                status = ShellStatus.DENIED,
                elevated = true,
            )
        }
        return executor.execute(
            command,
            elevated = true,
            timeoutMillis = timeoutMillis.coerceIn(1, ShellLimits.MAX_TIMEOUT_MILLIS),
        )
    }

    /** Runs [command] unprivileged. Always available; needs no probe. */
    suspend fun execute(
        command: String,
        timeoutMillis: Long = ShellLimits.DEFAULT_TIMEOUT_MILLIS,
    ): ShellResult = executor.execute(
        command,
        elevated = false,
        timeoutMillis = timeoutMillis.coerceIn(1, ShellLimits.MAX_TIMEOUT_MILLIS),
    )

    /** Drops the cached probe, e.g. after the user changes their root manager's settings. */
    fun invalidateCapability() {
        cached = null
    }

    private companion object {
        const val CAPABILITY_TTL_MILLIS = 60_000L
        const val PROBE_TIMEOUT_MILLIS = 10_000L
    }
}
