package com.mobilemcp.pro.core

import android.content.Context
import com.mobilemcp.pro.core.audit.AuditEventType
import com.mobilemcp.pro.core.audit.AuditLogger
import com.mobilemcp.pro.agent.ExecutionTracker
import com.mobilemcp.pro.core.identity.SecretBoundPairedDeviceRegistry
import com.mobilemcp.pro.core.mode.AppModeStore
import com.mobilemcp.pro.core.permission.AuthorizationDecision
import com.mobilemcp.pro.core.permission.AuthorizationManager
import com.mobilemcp.pro.core.permission.PersistentGrantStore
import com.mobilemcp.pro.core.permission.RequestGuard
import com.mobilemcp.pro.core.root.ProcessShellExecutor
import com.mobilemcp.pro.core.root.RootCommandHandler
import com.mobilemcp.pro.core.root.RootManager
import com.mobilemcp.pro.security.PairingSecretStore
import com.mobilemcp.pro.server.PrivilegedCommandGateway

/**
 * The one authorisation graph in the process.
 *
 * Every part of the app that asks or answers "may this be done?" has to be looking at the
 * same objects. If the settings screen revoked a grant on its own `AuthorizationManager`
 * while the control server consulted another, the owner would revoke root and watch root
 * commands keep succeeding — the exact class of "authorised here, unauthorised there" bug
 * that the single-enum, single-decision-point design exists to prevent. Holding one instance
 * of each collaborator here is what makes that guarantee hold across the process rather than
 * only within one class.
 *
 * This is a service locator, which is not the shape one would choose freely. Android
 * constructs `Service` and `Activity` itself and offers no injection point, and adding a DI
 * framework to a nine-class app would cost more than it explains. The scope is kept
 * deliberately narrow: authorisation only, created lazily, no Android context retained
 * beyond the application's own.
 *
 * Everything is built lazily. Most launches of this process are the Accessibility service
 * binding rather than the user opening the app, and a launch that never handles a privileged
 * command should not pay for a keystore read or a `SharedPreferences` parse.
 */
object SecurityServices {

    private lateinit var appContext: Context

    /** Called once, from [com.mobilemcp.pro.DroidPilotApplication]. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val isInitialized: Boolean get() = ::appContext.isInitialized

    val grantStore: PersistentGrantStore by lazy { PersistentGrantStore(appContext) }

    /** The owner's Pilot / Developer-Agent mode preference. Not itself a permission grant. */
    val modeStore: AppModeStore by lazy { AppModeStore(appContext) }

    /**
     * Live history of dispatched commands, for the Developer/Agent-mode execution panel.
     *
     * One instance per process, like [auditLogger], so the panel shown while the server is
     * running reflects what that same server actually dispatched.
     */
    val executionTracker: ExecutionTracker by lazy { ExecutionTracker() }

    val auditLogger: AuditLogger by lazy { AuditLogger() }

    /**
     * Reads the pairing secret afresh on every check rather than caching it.
     *
     * The cost is a keystore unwrap, and it is only paid on the privileged path, which is
     * rare by construction. What it buys is that regenerating the secret invalidates every
     * grant on the very next command instead of at the next restart — the identity grants
     * are keyed to simply stops existing. A cached secret would keep a revoked device
     * authorised for as long as the process lived.
     */
    val pairedDevices: SecretBoundPairedDeviceRegistry by lazy {
        SecretBoundPairedDeviceRegistry {
            runCatching { PairingSecretStore(appContext).peek() }.getOrNull()
        }
    }

    val authorization: AuthorizationManager by lazy {
        AuthorizationManager(
            store = grantStore,
            pairedDevices = pairedDevices,
            onAudit = { decision, deviceId, permission, initiator ->
                // Only refusals are recorded from here. A successful decision is followed
                // immediately by the handler's own SHELL_EXECUTED or ROOT_EXECUTED record,
                // which carries the exit code and output sizes; logging both would put two
                // entries in the trail for one command and make it read as though twice as
                // much happened as did.
                if (decision is AuthorizationDecision.Denied) {
                    auditLogger.record(
                        type = AuditEventType.AUTHORIZATION_DENIED,
                        deviceId = deviceId,
                        permission = permission,
                        initiator = initiator,
                        success = false,
                        detail = decision.message,
                    )
                }
            },
        )
    }

    val rootManager: RootManager by lazy { RootManager(ProcessShellExecutor()) }

    private val requestGuard: RequestGuard by lazy { RequestGuard() }

    val privilegedCommands: PrivilegedCommandGateway by lazy {
        PrivilegedCommandGateway(
            RootCommandHandler(
                rootManager = rootManager,
                authorization = authorization,
                requestGuard = requestGuard,
                audit = auditLogger,
            ),
        )
    }

    /**
     * Called after the owner regenerates the pairing secret.
     *
     * The grants are already inert at that point — they name a device id no peer can present
     * any more — so this only clears the dead records and drops the root-capability cache.
     * It is housekeeping, and is written so that forgetting to call it could not by itself
     * leave anyone authorised.
     */
    fun onPairingSecretRegenerated() {
        grantStore.forgetAllExcept(pairedDevices.currentDeviceId())
        rootManager.invalidateCapability()
        auditLogger.record(
            type = AuditEventType.DEVICE_UNPAIRED,
            detail = "Pairing secret regenerated; all previous authorisations are void",
        )
    }
}
