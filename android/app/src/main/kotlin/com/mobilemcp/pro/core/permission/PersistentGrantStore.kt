package com.mobilemcp.pro.core.permission

import android.content.Context
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A [GrantStore] that survives the process.
 *
 * The in-memory store was fine for tests and wrong for the product: grants that vanish when
 * Android reclaims the app mean an owner who granted root an hour ago silently has none,
 * and — more importantly in the other direction — a *revocation* would not have outlived the
 * process either. Authorisation state that does not survive a restart is not authorisation
 * state.
 *
 * ### Why the storage is two lambdas
 *
 * The class depends on "read a string, write a string" rather than on `SharedPreferences`,
 * so the decision logic can be tested exhaustively on the JVM with no Android runtime and no
 * test-time downloads. The Android binding is the secondary constructor and is the only part
 * that knows where the bytes actually go. This mirrors how [AuthorizationManager] keeps the
 * deciding separate from the storing, and for the same reason: the part that must be right
 * should be the part that is cheapest to test.
 *
 * ### What is stored
 *
 * Grant records only — device id, permission, duration, and the timestamps that make expiry
 * and single-use consumption decidable. No secret is written here. The device id is a hash
 * of the pairing secret, not the secret itself, and is not confidential.
 *
 * ### Failure is closed
 *
 * If the stored JSON cannot be parsed — a partial write, a downgrade, deliberate tampering —
 * the store reports **no grants** rather than throwing or salvaging what it can. The cost of
 * getting that backwards is honouring a root grant on the strength of a corrupt file, so the
 * only acceptable reading of unreadable authorisation data is "authorised for nothing".
 */
class PersistentGrantStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) : GrantStore {

    constructor(context: Context) : this(
        readRaw = {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_GRANTS, null)
        },
        writeRaw = { encoded ->
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                // commit(), not apply(). The caller has just made an authorisation decision
                // and the command it authorises is about to run; apply() is asynchronous,
                // and a process death inside that window would lose a revocation.
                .putString(KEY_GRANTS, encoded)
                .commit()
        },
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Mirrors the persisted set so a decision does not parse JSON on every command.
     *
     * Only ever mutated under the same lock that writes, so the two cannot disagree.
     */
    private var cache: MutableMap<String, Grant> = load()

    @Synchronized
    override fun grantsFor(deviceId: String): List<Grant> =
        cache.values.filter { it.deviceId == deviceId }

    @Synchronized
    override fun put(grant: Grant) {
        cache[grant.id] = grant
        persist()
    }

    @Synchronized
    override fun replace(grant: Grant) {
        cache[grant.id] = grant
        persist()
    }

    @Synchronized
    override fun all(): List<Grant> = cache.values.toList()

    /**
     * Drops grants belonging to devices that can no longer connect.
     *
     * Called after the pairing secret is regenerated. Those grants are already inert — the
     * paired-device registry reports the old id as unpaired — so this is housekeeping, and
     * is deliberately not the thing standing between a revoked device and a root shell.
     */
    @Synchronized
    fun forgetAllExcept(deviceId: String?) {
        val before = cache.size
        cache = cache.filterValues { it.deviceId == deviceId }.toMutableMap()
        if (cache.size != before) persist()
    }

    @Synchronized
    fun clear() {
        cache.clear()
        persist()
    }

    private fun persist() {
        writeRaw(json.encodeToString(ListSerializer(Grant.serializer()), cache.values.toList()))
    }

    private fun load(): MutableMap<String, Grant> {
        val stored = readRaw() ?: return mutableMapOf()
        return try {
            json.decodeFromString(ListSerializer(Grant.serializer()), stored)
                .associateBy { it.id }
                .toMutableMap()
        } catch (e: Exception) {
            // Deliberately empty rather than partial. See the class docs.
            Log.w(TAG, "Stored grants unreadable (${e.javaClass.simpleName}); treating as none")
            mutableMapOf()
        }
    }

    private companion object {
        const val TAG = "PersistentGrantStore"
        const val PREFS_NAME = "droidpilot_grants"
        const val KEY_GRANTS = "grants"
    }
}
