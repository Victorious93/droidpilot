package com.mobilemcp.pro.core.audit

import com.mobilemcp.pro.core.permission.Initiator
import com.mobilemcp.pro.core.permission.RemotePermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** What happened. Distinct types so the owner can filter for the things that matter. */
enum class AuditEventType {
    DEVICE_PAIRED,
    DEVICE_UNPAIRED,
    PERMISSION_GRANTED,
    PERMISSION_REVOKED,
    AUTHORIZATION_ALLOWED,
    AUTHORIZATION_DENIED,
    SHELL_EXECUTED,
    ROOT_EXECUTED,
    ROOT_SESSION_OPENED,
    ROOT_SESSION_CLOSED,
    REMOTE_CONNECTED,
    REMOTE_DISCONNECTED,

    /** The owner emptied the trail. Its own type: clearing a log is not a revocation. */
    AUDIT_CLEARED,
}

/**
 * One entry in the audit trail.
 *
 * The fields are chosen so the owner can answer "who ran what as root on my phone, when,
 * and who said they could" without reading anything else. Fields that could carry a secret
 * are absent by construction rather than filtered later — see [AuditLogger].
 */
@Serializable
data class AuditEvent(
    val id: String,
    val timestampMillis: Long,
    val type: AuditEventType,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val permission: RemotePermission? = null,
    val initiator: Initiator? = null,
    /** The command as issued. Never a credential — see the class docs on [AuditLogger]. */
    val command: String? = null,
    val exitCode: Int? = null,
    val durationMillis: Long? = null,
    val success: Boolean? = null,
    /** Sizes rather than contents: enough to spot an exfiltration, without storing the data. */
    val stdoutBytes: Int? = null,
    val stderrBytes: Int? = null,
    val detail: String? = null,
) {
    /** One-line rendering for the log screen. */
    fun describe(): String = buildString {
        append(type.name)
        deviceName?.let { append(" · ").append(it) }
        permission?.let { append(" · ").append(it.wireName) }
        initiator?.let { if (it == Initiator.AI) append(" · AI-initiated") }
        command?.let { append(" · ").append(it.take(120)) }
        exitCode?.let { append(" · exit ").append(it) }
    }
}

/**
 * Records privileged and security-relevant operations.
 *
 * ### What is never recorded
 *
 * Command **output** is recorded only as byte counts, never as content. A root command's
 * stdout routinely contains exactly what must not be written to a log that the owner may
 * later export or share — `cat` of a key file, a token in an environment dump, a password
 * in a config. Storing sizes preserves the forensic value (something large left the device
 * at 03:14) without creating a second copy of the secret.
 *
 * Command **text** is recorded, because an audit log that cannot say what was run is not an
 * audit log. That is a deliberate, stated trade-off: a command line can itself contain a
 * secret if someone types one, which is why [redactCommand] strips the argument of the
 * handful of flags conventionally used to pass them.
 *
 * The log is capped and in-memory. Persisting it is a natural next step; doing so must not
 * change what is stored, only where.
 */
class AuditLogger(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _events = MutableStateFlow<List<AuditEvent>>(emptyList())
    val events: StateFlow<List<AuditEvent>> = _events.asStateFlow()

    private var sequence = 0L

    @Synchronized
    fun record(
        type: AuditEventType,
        deviceId: String? = null,
        deviceName: String? = null,
        permission: RemotePermission? = null,
        initiator: Initiator? = null,
        command: String? = null,
        exitCode: Int? = null,
        durationMillis: Long? = null,
        success: Boolean? = null,
        stdoutBytes: Int? = null,
        stderrBytes: Int? = null,
        detail: String? = null,
    ): AuditEvent {
        val now = clock()
        val event = AuditEvent(
            id = "audit-$now-${sequence++}",
            timestampMillis = now,
            type = type,
            deviceId = deviceId,
            deviceName = deviceName,
            permission = permission,
            initiator = initiator,
            command = command?.let(::redactCommand),
            exitCode = exitCode,
            durationMillis = durationMillis,
            success = success,
            stdoutBytes = stdoutBytes,
            stderrBytes = stderrBytes,
            detail = detail,
        )

        _events.update { existing ->
            val appended = existing + event
            if (appended.size > maxEntries) appended.takeLast(maxEntries) else appended
        }
        return event
    }

    fun eventsFor(deviceId: String): List<AuditEvent> = _events.value.filter { it.deviceId == deviceId }

    fun eventsOfType(vararg types: AuditEventType): List<AuditEvent> =
        _events.value.filter { it.type in types }

    /** Clears the trail. An owner action, and itself worth recording. */
    @Synchronized
    fun clear() {
        _events.value = emptyList()
        record(AuditEventType.AUDIT_CLEARED, detail = "Audit log cleared by owner")
    }

    /** Plain-text export for sharing or filing a bug. Subject to the same redaction. */
    fun export(): String = _events.value.joinToString("\n") { event ->
        "${event.timestampMillis}\t${event.describe()}"
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 2_000

        /**
         * Flags that conventionally take a secret as their argument.
         *
         * This is a courtesy, not a guarantee — a secret typed in any other shape is
         * recorded verbatim, and no pattern list can fix that. It catches the common,
         * accidental cases; the real protection is that output is never stored at all.
         */
        val SECRET_FLAGS = listOf(
            "--password", "--passwd", "--token", "--api-key", "--apikey",
            "--secret", "--auth", "-p", "--pass",
        )

        fun redactCommand(command: String): String {
            var redacted = command
            SECRET_FLAGS.forEach { flag ->
                redacted = Regex("(${Regex.escape(flag)})(\\s+|=)(\\S+)")
                    .replace(redacted) { "${it.groupValues[1]}${it.groupValues[2]}«redacted»" }
            }
            return redacted.take(MAX_COMMAND_CHARS)
        }

        const val MAX_COMMAND_CHARS = 2_000
    }
}
