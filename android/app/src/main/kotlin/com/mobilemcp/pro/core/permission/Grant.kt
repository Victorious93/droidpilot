package com.mobilemcp.pro.core.permission

import kotlinx.serialization.Serializable

/**
 * How long an authorisation lasts.
 *
 * Every shape carries its own expiry rule, so "is this still valid?" is answered by the
 * grant itself rather than by whoever happens to be checking. That matters because the
 * check runs at execution time, on every command — a grant that has to be interpreted
 * correctly by each call site is a grant that will eventually be interpreted wrongly by one
 * of them.
 */
@Serializable
sealed interface GrantDuration {

    /** Valid for exactly one successful use, then spent. */
    @Serializable
    data object Once : GrantDuration

    /** Valid until [expiresAtMillis], wall-clock. */
    @Serializable
    data class Until(val expiresAtMillis: Long) : GrantDuration

    /** Valid until the owner revokes it. */
    @Serializable
    data object UntilRevoked : GrantDuration
}

/**
 * One owner decision: this device may exercise this permission, for this long.
 *
 * A grant is deliberately not a token the requester holds. It is a record this device keeps
 * and re-reads on every command, so revoking it takes effect on the next command rather
 * than whenever a cached credential happens to expire. Nothing the remote peer sends can
 * assert its own authority.
 */
@Serializable
data class Grant(
    val id: String,
    /** Stable identity of the authorised device — see `DeviceIdentity`. */
    val deviceId: String,
    val permission: RemotePermission,
    val duration: GrantDuration,
    val grantedAtMillis: Long,
    /** Set when the owner revokes; a revoked grant is kept for the audit trail. */
    val revokedAtMillis: Long? = null,
    /** Set when a [GrantDuration.Once] grant is spent. */
    val consumedAtMillis: Long? = null,
    val lastUsedAtMillis: Long? = null,
    /** Free text recorded at grant time, e.g. "Approved on device by owner". */
    val note: String? = null,
) {
    val isRevoked: Boolean get() = revokedAtMillis != null

    val isConsumed: Boolean get() = consumedAtMillis != null

    /** Wall-clock expiry, or `null` for grants that do not expire on their own. */
    val expiresAtMillis: Long?
        get() = (duration as? GrantDuration.Until)?.expiresAtMillis

    fun isExpiredAt(nowMillis: Long): Boolean = when (duration) {
        is GrantDuration.Until -> nowMillis >= duration.expiresAtMillis
        GrantDuration.Once, GrantDuration.UntilRevoked -> false
    }

    /**
     * Whether this grant authorises anything right now.
     *
     * All three conditions are checked together and at the point of use. Splitting them
     * across call sites is how an expired-but-not-revoked grant ends up honoured.
     */
    fun isActiveAt(nowMillis: Long): Boolean =
        !isRevoked && !isConsumed && !isExpiredAt(nowMillis)
}

/**
 * Why a request was refused.
 *
 * Distinct reasons because they call for different responses from the operator: an expired
 * grant needs renewing, a revoked one was a deliberate act, and a missing capability is not
 * a permission problem at all.
 */
enum class DenialReason(val explanation: String) {
    UNKNOWN_DEVICE("The requesting device is not paired with this device"),
    NO_GRANT("The owner has not granted this permission to that device"),
    REVOKED("The owner revoked this permission"),
    EXPIRED("The authorisation has expired"),
    ALREADY_USED("That was a single-use authorisation and has already been used"),
    AI_ROOT_REQUIRED("Root was authorised for the device but not for AI-initiated commands"),
    CAPABILITY_UNAVAILABLE("This device cannot perform that operation"),
}

/** The outcome of an authorisation check. */
sealed interface AuthorizationDecision {

    data class Allowed(val grant: Grant) : AuthorizationDecision

    data class Denied(
        val reason: DenialReason,
        val permission: RemotePermission,
    ) : AuthorizationDecision {
        val message: String get() = reason.explanation
    }
}

/**
 * Who is asking.
 *
 * The distinction exists solely to enforce [RemotePermission.AI_ROOT]: a root command
 * originating from the model must clear a second gate that one typed by a person at a
 * paired device does not. Making the initiator an explicit argument means no call site can
 * forget to say which it is.
 */
enum class Initiator {
    /** A person acting through a paired device. */
    REMOTE_DEVICE,

    /** The AI subsystem, acting on its own reasoning. */
    AI,
}
