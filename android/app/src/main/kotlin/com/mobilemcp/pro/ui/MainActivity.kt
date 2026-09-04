package com.mobilemcp.pro.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mobilemcp.pro.R
import com.mobilemcp.pro.agent.ExecutionStep
import com.mobilemcp.pro.core.NetworkAddresses
import com.mobilemcp.pro.core.SecurityServices
import com.mobilemcp.pro.core.audit.AuditEventType
import com.mobilemcp.pro.core.mode.AppMode
import com.mobilemcp.pro.core.permission.GrantDuration
import com.mobilemcp.pro.core.permission.RemotePermission
import com.mobilemcp.pro.databinding.ActivityMainBinding
import com.mobilemcp.pro.security.PairingSecret
import com.mobilemcp.pro.security.PairingSecretStore
import com.mobilemcp.pro.server.LogEntry
import com.mobilemcp.pro.server.ServerController
import com.mobilemcp.pro.server.ServerState
import com.mobilemcp.pro.service.DroidPilotAccessibilityService
import com.mobilemcp.pro.service.ServerForegroundService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The control screen.
 *
 * This Activity deliberately owns nothing but views. It starts and stops
 * [ServerForegroundService] through intents and renders [ServerController]'s state; the
 * server's lifetime is entirely independent of it. In the previous design the Activity
 * held the server itself, so rotating the device tore it down mid-session.
 *
 * Being purely a view is also what makes rotation, backgrounding and process death
 * uneventful here: there is no state to save because the Activity holds none.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var secretStore: PairingSecretStore

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var secretRevealed = false

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Denial is not fatal — the server runs regardless — so this explains the
            // consequence rather than nagging or blocking.
            Snackbar.make(binding.root, R.string.notification_permission_rationale, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secretStore = PairingSecretStore(this)

        wireActions()
        wireMode()
        observeServerState()
        observeLogs()
        observeExecutionHistory()
        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // Accessibility and network state can both change while the app is backgrounded —
        // the user may have just come back from the settings screen we sent them to.
        renderAccessibilityState()
        renderAddresses()
        renderGrants()
    }

    // ------------------------------------------------------------------- mode

    /**
     * Renders the persisted [AppMode] and lets the owner switch it.
     *
     * The toggle only changes what this screen shows and what `get_capabilities` reports —
     * see [AppMode]'s own docs. It never grants a permission, so there is deliberately no
     * confirmation dialog here the way there is for [confirmGrant].
     */
    private fun wireMode() {
        renderMode(SecurityServices.modeStore.get())

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == binding.btnModeDeveloperAgent.id) {
                AppMode.DEVELOPER_AGENT
            } else {
                AppMode.PILOT
            }
            SecurityServices.modeStore.set(mode)
            renderMode(mode)
        }
    }

    private fun renderMode(mode: AppMode) {
        val checkedId = if (mode == AppMode.DEVELOPER_AGENT) {
            binding.btnModeDeveloperAgent.id
        } else {
            binding.btnModePilot.id
        }
        if (binding.modeToggleGroup.checkedButtonId != checkedId) {
            binding.modeToggleGroup.check(checkedId)
        }
        binding.tvModeDescription.setText(
            if (mode == AppMode.DEVELOPER_AGENT) {
                R.string.mode_developer_agent_description
            } else {
                R.string.mode_pilot_description
            },
        )
        binding.cardExecution.isVisible(mode == AppMode.DEVELOPER_AGENT)
    }

    // -------------------------------------------------------- execution history

    private fun observeExecutionHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SecurityServices.executionTracker.steps.collectLatest(::renderExecution)
            }
        }
    }

    private fun renderExecution(steps: List<ExecutionStep>) {
        binding.tvExecutionSummary.text = if (steps.isEmpty()) {
            getString(R.string.execution_empty)
        } else {
            steps.takeLast(EXECUTION_SUMMARY_ROWS).joinToString("\n") { step ->
                "${statusGlyph(step.status)} ${step.command} — ${step.summary}"
            }
        }
        binding.btnClearExecution.setOnClickListener { SecurityServices.executionTracker.clear() }
        binding.btnViewExecutionDetails.setOnClickListener { showExecutionDetails(steps) }
    }

    private fun statusGlyph(status: com.mobilemcp.pro.agent.ActionStatus): String = when (status) {
        com.mobilemcp.pro.agent.ActionStatus.SUCCESS -> "✓"
        com.mobilemcp.pro.agent.ActionStatus.FAILED -> "✗"
        com.mobilemcp.pro.agent.ActionStatus.BLOCKED -> "⛔"
        com.mobilemcp.pro.agent.ActionStatus.REQUIRES_PERMISSION -> "🔒"
        com.mobilemcp.pro.agent.ActionStatus.REQUIRES_USER -> "?"
        com.mobilemcp.pro.agent.ActionStatus.RETRYABLE -> "↻"
    }

    /** The expanded view: every recorded step, in order, with its error when it failed. */
    private fun showExecutionDetails(steps: List<ExecutionStep>) {
        val body = if (steps.isEmpty()) {
            getString(R.string.execution_details_empty)
        } else {
            steps.reversed().joinToString("\n\n") { step ->
                buildString {
                    append(formatTimestamp(step.startedAtMillis))
                    append("  ")
                    append(statusGlyph(step.status))
                    append("  ")
                    append(step.command)
                    append(" — ")
                    append(step.status.name)
                    if (step.error != null) {
                        append("\n")
                        append(step.error)
                    }
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.execution_details_title)
            .setMessage(body)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    // ------------------------------------------------------------------- actions

    private fun wireActions() {
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnToggleServer.setOnClickListener {
            if (ServerController.state.value is ServerState.Running) stopServer() else startServer()
        }

        binding.btnRevealSecret.setOnClickListener {
            secretRevealed = !secretRevealed
            renderPairingSecret()
        }

        binding.btnCopyPairing.setOnClickListener { copyPairingUri() }

        binding.btnRegenerateSecret.setOnClickListener { confirmRegenerate() }

        binding.btnClearLog.setOnClickListener { ServerController.clearLogs() }

        binding.btnGrantShell.setOnClickListener {
            confirmGrant(RemotePermission.REMOTE_SHELL, warning = null)
        }
        binding.btnGrantRoot.setOnClickListener {
            confirmGrant(RemotePermission.REMOTE_ROOT, warning = R.string.grant_root_warning)
        }
        binding.btnGrantAiRoot.setOnClickListener {
            confirmGrant(RemotePermission.AI_ROOT, warning = R.string.grant_ai_root_warning)
        }
        binding.btnRevokeAll.setOnClickListener { revokeAll() }
        binding.btnViewAudit.setOnClickListener { showAuditLog() }

        // Lets the log pane be dragged. `android:scrollbars` alone renders the bar but does
        // not make the view scrollable.
        binding.tvLog.movementMethod = android.text.method.ScrollingMovementMethod()
    }

    private fun startServer() {
        if (!isAccessibilityServiceEnabled()) {
            Snackbar.make(binding.root, R.string.error_accessibility_required, Snackbar.LENGTH_LONG).show()
            return
        }

        val port = binding.etPort.text?.toString()?.toIntOrNull()
        if (port == null || port !in MIN_PORT..MAX_PORT) {
            binding.portLayout.error = getString(R.string.error_invalid_port)
            return
        }
        binding.portLayout.error = null

        val bindAddress = if (binding.switchLoopback.isChecked) {
            ServerForegroundService.LOOPBACK_BIND_ADDRESS
        } else {
            ServerForegroundService.DEFAULT_BIND_ADDRESS
        }

        ContextCompat.startForegroundService(
            this,
            ServerForegroundService.startIntent(this, port, bindAddress),
        )
    }

    private fun stopServer() {
        startService(ServerForegroundService.stopIntent(this))
    }

    private fun copyPairingUri() {
        val secret = secretStore.getOrCreate()
        val host = NetworkAddresses.findLocalAddresses(this).firstOrNull()?.ip ?: "127.0.0.1"
        val port = binding.etPort.text?.toString()?.toIntOrNull() ?: ServerForegroundService.DEFAULT_PORT

        val clip = ClipData.newPlainText("DroidPilot pairing", PairingSecret.pairingUri(host, port, secret))
        // Flagged sensitive so Android 13+ omits the value from the clipboard preview toast
        // — otherwise copying the secret displays it on screen to anyone nearby.
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(ClipDescriptionCompat.EXTRA_IS_SENSITIVE, true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)

        Snackbar.make(binding.root, R.string.copied_to_clipboard, Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmRegenerate() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.regenerate_title)
            .setMessage(R.string.regenerate_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.regenerate_confirm) { _, _ ->
                secretStore.regenerate()
                // Grants are keyed to a device id derived from the secret, so they are
                // already inert at this point — the identity they name can no longer
                // connect. This clears the dead records and refreshes what is displayed.
                SecurityServices.onPairingSecretRegenerated()
                renderPairingSecret()
                renderGrants()
                Snackbar.make(binding.root, R.string.regenerated, Snackbar.LENGTH_LONG).show()
                // Existing sessions authenticated with the old secret must not survive it.
                if (ServerController.state.value is ServerState.Running) {
                    stopServer()
                }
            }
            .show()
    }

    // -------------------------------------------------------- remote command access

    /**
     * Describes what the paired client may currently do.
     *
     * Phrased as a list of what *is* allowed rather than a set of toggles, because the
     * honest default is nothing: a reader glancing at this card should be able to tell at
     * once whether anything is authorised, without interpreting several switch positions.
     */
    private fun renderGrants() {
        val deviceId = SecurityServices.pairedDevices.currentDeviceId()
        if (deviceId == null) {
            binding.tvGrants.setText(R.string.no_pairing_secret)
            return
        }

        val active = SecurityServices.authorization.activeGrants(deviceId)
        binding.tvGrants.text = if (active.isEmpty()) {
            getString(R.string.grants_none)
        } else {
            buildString {
                append(getString(R.string.grants_prefix))
                active.sortedBy { it.permission.ordinal }.forEach { grant ->
                    append("\n• ")
                    append(grant.permission.wireName)
                    append(" — ")
                    append(describe(grant.duration))
                }
            }
        }
    }

    private fun describe(duration: GrantDuration): String = when (duration) {
        GrantDuration.Once -> getString(R.string.grant_once)
        GrantDuration.UntilRevoked -> getString(R.string.grant_until_revoked)
        is GrantDuration.Until -> {
            val remaining = duration.expiresAtMillis - System.currentTimeMillis()
            val minutes = (remaining / 60_000L).coerceAtLeast(0)
            "expires in ${minutes}m"
        }
    }

    /**
     * Asks for the duration, then issues the grant.
     *
     * The duration is a required choice rather than a default, because "until I revoke it"
     * is the most permissive option and should never be what happens to someone who tapped
     * through a dialog without reading it.
     */
    private fun confirmGrant(permission: RemotePermission, warning: Int?) {
        val deviceId = SecurityServices.pairedDevices.currentDeviceId()
        if (deviceId == null) {
            Snackbar.make(binding.root, R.string.no_pairing_secret, Snackbar.LENGTH_LONG).show()
            return
        }

        val labels = arrayOf(
            getString(R.string.grant_once),
            getString(R.string.grant_15_minutes),
            getString(R.string.grant_1_hour),
            getString(R.string.grant_until_revoked),
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grant_duration_title)
            .apply { warning?.let { setMessage(it) } }
            .setNegativeButton(R.string.btn_cancel, null)
            .setItems(labels) { _, which ->
                val now = System.currentTimeMillis()
                val duration = when (which) {
                    0 -> GrantDuration.Once
                    1 -> GrantDuration.Until(now + 15 * 60_000L)
                    2 -> GrantDuration.Until(now + 60 * 60_000L)
                    else -> GrantDuration.UntilRevoked
                }
                SecurityServices.authorization.grant(
                    deviceId = deviceId,
                    permission = permission,
                    duration = duration,
                    note = "Granted on device by the owner",
                )
                SecurityServices.auditLogger.record(
                    type = AuditEventType.PERMISSION_GRANTED,
                    deviceId = deviceId,
                    permission = permission,
                    detail = "Granted on device by the owner",
                )
                renderGrants()
                Snackbar.make(binding.root, R.string.granted_toast, Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun revokeAll() {
        val deviceId = SecurityServices.pairedDevices.currentDeviceId() ?: return
        val revoked = SecurityServices.authorization.revokeAll(deviceId)
        if (revoked > 0) {
            SecurityServices.auditLogger.record(
                type = AuditEventType.PERMISSION_REVOKED,
                deviceId = deviceId,
                detail = "All command access revoked on device by the owner",
            )
        }
        renderGrants()
        Snackbar.make(binding.root, R.string.revoked_toast, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Shows the privileged-operation trail.
     *
     * Only the privileged events, not every log line: this answers "what has been run with
     * elevated access on my phone", and padding it with connection chatter would bury the
     * one entry that matters.
     */
    private fun showAuditLog() {
        val events = SecurityServices.auditLogger.events.value
            .filter { it.type in PRIVILEGED_EVENTS }
            .takeLast(100)

        val body = if (events.isEmpty()) {
            getString(R.string.audit_empty)
        } else {
            events.reversed().joinToString("\n\n") { event ->
                "${formatTimestamp(event.timestampMillis)}\n${event.describe()}"
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.audit_title)
            .setMessage(body)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun formatTimestamp(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(millis))

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ----------------------------------------------------------------- rendering

    private fun observeServerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerController.state.collectLatest(::renderServerState)
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerController.logs.collectLatest(::renderLogs)
            }
        }
    }

    private fun renderServerState(state: ServerState) {
        val (label, colorRes) = when (state) {
            ServerState.Stopped -> getString(R.string.status_stopped) to R.color.status_stopped
            ServerState.Starting -> getString(R.string.status_starting) to R.color.status_pending
            is ServerState.Running -> getString(R.string.status_running) to R.color.status_ok
            is ServerState.Failed -> getString(R.string.status_failed) to R.color.status_error
        }

        binding.tvStatus.text = label
        setDotColor(binding.statusIndicator, colorRes)

        val running = state is ServerState.Running
        binding.btnToggleServer.setText(if (running) R.string.btn_stop_server else R.string.btn_start_server)
        binding.etPort.isEnabled = !running
        binding.switchLoopback.isEnabled = !running

        binding.tvConnections.text = when {
            state is ServerState.Running && state.connectedClients > 0 ->
                resources.getQuantityString(
                    R.plurals.connections_count,
                    state.connectedClients,
                    state.connectedClients,
                )
            else -> getString(R.string.connections_none)
        }

        if (state is ServerState.Failed) {
            Snackbar.make(binding.root, state.reason, Snackbar.LENGTH_LONG).show()
        }

        renderPairingSecret()
    }

    private fun renderLogs(entries: List<LogEntry>) {
        // The whole buffer is rebuilt from a bounded list on each emission. The previous
        // implementation appended to the TextView and then trimmed by reading its entire
        // contents back out as a String per line, which turned a busy session into
        // quadratic main-thread work.
        binding.tvLog.text = entries.joinToString("\n") { entry ->
            "[${timeFormat.format(Date(entry.timestampMillis))}] ${entry.message}"
        }
        scrollLogToBottom()
    }

    /**
     * Keeps the newest line visible.
     *
     * The TextView scrolls itself, so this measures the rendered layout and jumps to the
     * end. Posted rather than called inline because the layout has not been measured yet at
     * the moment the text is set, and scrolling an unmeasured view does nothing.
     */
    private fun scrollLogToBottom() = binding.tvLog.post {
        val layout = binding.tvLog.layout ?: return@post
        val overflow = layout.getLineBottom(layout.lineCount - 1) - binding.tvLog.height +
            binding.tvLog.paddingTop + binding.tvLog.paddingBottom
        binding.tvLog.scrollTo(0, overflow.coerceAtLeast(0))
    }

    private fun renderPairingSecret() {
        val secret = secretStore.getOrCreate()
        binding.tvPairingSecret.text = if (secretRevealed) {
            PairingSecret.encode(secret)
        } else {
            getString(R.string.pairing_hidden)
        }
        binding.btnRevealSecret.setText(if (secretRevealed) R.string.btn_hide else R.string.btn_reveal)
        binding.tvFingerprint.text =
            getString(R.string.fingerprint_label, PairingSecret.fingerprint(secret))
    }

    private fun renderAccessibilityState() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvAccessibilityStatus.setText(
            if (enabled) R.string.accessibility_on else R.string.accessibility_off
        )
        setDotColor(
            binding.accessibilityIndicator,
            if (enabled) R.color.status_ok else R.color.status_error,
        )
        binding.tvAccessibilityHint.isVisible(!enabled)
    }

    private fun renderAddresses() {
        val addresses = NetworkAddresses.findLocalAddresses(this)
        binding.tvAddresses.text = if (addresses.isEmpty()) {
            getString(R.string.address_unknown)
        } else {
            buildString {
                appendLine(getString(R.string.address_prefix))
                addresses.forEach { append("  ${it.ip}  (${it.interfaceName})\n") }
            }.trimEnd()
        }
    }

    /**
     * Reports whether *this* service is enabled.
     *
     * `getEnabledAccessibilityServiceList` is queried with `FEEDBACK_ALL_MASK`: filtering
     * by `FEEDBACK_GENERIC`, as the previous version did, silently misses the service on
     * OEM builds that report a different feedback type, leaving the UI claiming the
     * permission was never granted.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val target = DroidPilotAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == target }
    }

    private fun setDotColor(view: android.view.View, colorRes: Int) {
        val dot = view.background as? GradientDrawable ?: GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            view.background = it
        }
        dot.setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun android.view.View.isVisible(visible: Boolean) {
        visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** `ClipDescription.EXTRA_IS_SENSITIVE` is only public from API 33; the key is stable. */
    private object ClipDescriptionCompat {
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }

    private companion object {

        /** The events the audit dialog shows: privileged operations and authority changes. */
        val PRIVILEGED_EVENTS = setOf(
            AuditEventType.SHELL_EXECUTED,
            AuditEventType.ROOT_EXECUTED,
            AuditEventType.AUTHORIZATION_DENIED,
            AuditEventType.PERMISSION_GRANTED,
            AuditEventType.PERMISSION_REVOKED,
            AuditEventType.DEVICE_UNPAIRED,
            AuditEventType.AUDIT_CLEARED,
        )
        // Ports below 1024 need privileges an app does not have, so they are rejected up
        // front rather than surfacing later as an opaque bind failure.
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** Rows shown inline in the execution card; the details dialog shows the full history. */
        const val EXECUTION_SUMMARY_ROWS = 8
    }
}
