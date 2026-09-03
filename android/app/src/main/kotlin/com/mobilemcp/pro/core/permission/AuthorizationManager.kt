package com.mobilemcp.pro.core.permission

/**
 * Stores grants. Implementations persist; the in-memory one backs the tests.
 *
 * Kept as an interface so [AuthorizationManager] — the part that decides — carries no
 * Android or storage dependency and can be tested exhaustively on the JVM.
 */
interface GrantStore {
    fun grantsFor(deviceId: String): List<Grant>
    fun put(grant: Grant)
    fun replace(grant: Grant)
    fun all(): List<Grant>
}

/** Straightforward in-memory store. Used by tests and as the base for a persistent one. */
class InMemoryGrantStore : GrantStore {
    private val grants = LinkedHashMap<String, Grant>()

    @Synchronized
    override fun grantsFor(deviceId: String): List<Grant> =
        grants.values.filter { it.deviceId == deviceId }

    @Synchronized
    override fun put(grant: Grant) {
        grants[grant.id] = grant
    }

    @Synchronized
    override fun replace(grant: Grant) {
        grants[grant.id] = grant
    }

    @Synchronized
    override fun all(): List<Grant> = grants.values.toList()
}

/** Devices the owner has paired. Pairing establishes identity; it grants nothing. */
interface PairedDeviceRegistry {
    fun isPaired(deviceId: String): Boolean
}

/**
 * The single point at which "may this device do this?" is answered.
 *
 * Every privileged operation routes through [authorize]. There is intentionally no second
 * path, no cached verdict and no bypass: the decision is recomputed from stored grants on
 * every command, which is what makes revocation take effect immediately rather than
 * whenever some previously issued token happens to lapse.
 *
 * ### The boundary
 *
 * A request is allowed only when **all** of these hold:
 *
 *  1. the requesting device is paired (identity is established),
 *  2. a grant exists for that device and permission,
 *  3. the grant is not revoked,
 *  4. the grant has not expired,
 *  5. a single-use grant has not already been spent,
 *  6. and for root initiated by the AI, [RemotePermission.AI_ROOT] is *separately* granted.
 *
 * Pairing is step 1 of six. It is not authorisation, and nothing here lets it become so.
 *
 * ### What this deliberately does not do
 *
 * It does not inspect the command. Once the owner has explicitly authorised
 * [RemotePermission.REMOTE_ROOT] for a device, root commands from that device are an
 * intended feature, and second-guessing them with a hard-coded blocklist would be
 * security theatre: it would fail closed on legitimate administration while stopping
 * nobody who can spell a command two ways. The boundary is owner authorisation plus device
 * identity plus a live grant — not a guess about which strings look dangerous.
 *
 * Command *preview* and confirmation are a separate, complementary concern belonging to the
 * UI layer, and are about avoiding mistakes rather than resisting an attacker.
 */
class AuthorizationManager(
    private val store: GrantStore,
    private val pairedDevices: PairedDeviceRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onAudit: (AuthorizationDecision, String, RemotePermission, Initiator) -> Unit = { _, _, _, _ -> },
) {

    /**
     * Decides whether [deviceId] may exercise [permission] right now.
     *
     * A [AuthorizationDecision.Allowed] result **consumes** a single-use grant as a side
     * effect, so callers must treat a successful decision as spending the authorisation
     * whether or not the operation then succeeds. Consuming on use rather than on
     * completion is the safe direction: a command that runs and then fails has still run.
     */
    @Synchronized
    fun authorize(
        deviceId: String,
        permission: RemotePermission,
        initiator: Initiator = Initiator.REMOTE_DEVICE,
    ): AuthorizationDecision {
        val decision = evaluate(deviceId, permission, initiator)
        onAudit(decision, deviceId, permission, initiator)
        return decision
    }

    private fun evaluate(
        deviceId: String,
        permission: RemotePermission,
        initiator: Initiator,
    ): AuthorizationDecision {
        if (!pairedDevices.isPaired(deviceId)) {
            return AuthorizationDecision.Denied(DenialReason.UNKNOWN_DEVICE, permission)
        }

        // Root initiated by the model needs AI_ROOT as well as REMOTE_ROOT. Evaluated first
        // and without consuming, so a refusal at the second gate cannot spend a single-use
        // grant — but the grant is held onto, because it has to be spent if the command is
        // ultimately allowed.
        val aiGate: Grant? =
            if (initiator == Initiator.AI && permission == RemotePermission.REMOTE_ROOT) {
                when (val aiDecision = evaluateSingle(deviceId, RemotePermission.AI_ROOT)) {
                    is AuthorizationDecision.Allowed -> aiDecision.grant
                    is AuthorizationDecision.Denied ->
                        return AuthorizationDecision.Denied(DenialReason.AI_ROOT_REQUIRED, permission)
                }
            } else {
                null
            }

        return when (val decision = evaluateSingle(deviceId, permission)) {
            is AuthorizationDecision.Allowed -> {
                // Both gates are spent, and only once the command is actually authorised.
                // Omitting the first is how "allow the model to do this once" silently
                // became "for as long as REMOTE_ROOT lives" — the opposite of the owner's
                // choice, on the permission where being wrong is least recoverable.
                aiGate?.let(::consumeIfSingleUse)
                consumeIfSingleUse(decision.grant)
                decision
            }
            is AuthorizationDecision.Denied -> decision
        }
    }

    /** Evaluates one permission without consuming anything. */
    private fun evaluateSingle(
        deviceId: String,
        permission: RemotePermission,
    ): AuthorizationDecision {
        val now = clock()
        val candidates = store.grantsFor(deviceId).filter { it.permission == permission }

        if (candidates.isEmpty()) {
            return AuthorizationDecision.Denied(DenialReason.NO_GRANT, permission)
        }

        candidates.firstOrNull { it.isActiveAt(now) }
            ?.let { return AuthorizationDecision.Allowed(it) }

        // Nothing is active. Report the most useful reason rather than a generic refusal:
        // "expired" tells the operator to renew, "revoked" tells them it was deliberate.
        val reason = when {
            candidates.any { !it.isRevoked && !it.isConsumed && it.isExpiredAt(now) } -> DenialReason.EXPIRED
            candidates.any { !it.isRevoked && it.isConsumed } -> DenialReason.ALREADY_USED
            candidates.all { it.isRevoked } -> DenialReason.REVOKED
            else -> DenialReason.NO_GRANT
        }
        return AuthorizationDecision.Denied(reason, permission)
    }

    private fun consumeIfSingleUse(grant: Grant) {
        val now = clock()
        val updated = if (grant.duration == GrantDuration.Once) {
            grant.copy(consumedAtMillis = now, lastUsedAtMillis = now)
        } else {
            grant.copy(lastUsedAtMillis = now)
        }
        store.replace(updated)
    }

    // --------------------------------------------------------------- owner actions

    /** Issues a grant. Only ever called in response to an explicit owner decision. */
    @Synchronized
    fun grant(
        deviceId: String,
        permission: RemotePermission,
        duration: GrantDuration,
        note: String? = null,
        id: String = "grant-${clock()}-${permission.wireName}-${deviceId.take(8)}",
    ): Grant {
        // Supersede any live grant for the same pair, so the newest owner decision is the
        // one in force and an older, longer-lived grant cannot outlive a deliberate change.
        store.grantsFor(deviceId)
            .filter { it.permission == permission && it.isActiveAt(clock()) }
            .forEach { store.replace(it.copy(revokedAtMillis = clock())) }

        return Grant(
            id = id,
            deviceId = deviceId,
            permission = permission,
            duration = duration,
            grantedAtMillis = clock(),
            note = note,
        ).also(store::put)
    }

    /**
     * Applies a [TrustLevel] as individual grants.
     *
     * The preset is expanded here and then forgotten; it is never consulted at execution
     * time. Root is not in any preset, so this can never confer it.
     */
    @Synchronized
    fun applyTrustLevel(
        deviceId: String,
        level: TrustLevel,
        duration: GrantDuration = GrantDuration.UntilRevoked,
    ): List<Grant> = level.includes.map { permission ->
        grant(deviceId, permission, duration, note = "Applied with ${level.name} preset")
    }

    /** Revokes one permission for one device. Takes effect on the next command. */
    @Synchronized
    fun revoke(deviceId: String, permission: RemotePermission): Int {
        val now = clock()
        val affected = store.grantsFor(deviceId)
            .filter { it.permission == permission && !it.isRevoked }
        affected.forEach { store.replace(it.copy(revokedAtMillis = now)) }
        return affected.size
    }

    /** Revokes everything for one device — the "remove this device" action. */
    @Synchronized
    fun revokeAll(deviceId: String): Int {
        val now = clock()
        val affected = store.grantsFor(deviceId).filter { !it.isRevoked }
        affected.forEach { store.replace(it.copy(revokedAtMillis = now)) }
        return affected.size
    }

    /** Live grants for a device, for display in the permissions UI. */
    @Synchronized
    fun activeGrants(deviceId: String): List<Grant> {
        val now = clock()
        return store.grantsFor(deviceId).filter { it.isActiveAt(now) }
    }

    /** Every permission currently in force for a device. */
    @Synchronized
    fun effectivePermissions(deviceId: String): Set<RemotePermission> =
        if (!pairedDevices.isPaired(deviceId)) emptySet()
        else activeGrants(deviceId).map { it.permission }.toSet()
}
