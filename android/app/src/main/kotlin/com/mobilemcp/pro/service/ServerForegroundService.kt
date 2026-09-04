package com.mobilemcp.pro.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mobilemcp.pro.BuildConfig
import com.mobilemcp.pro.R
import com.mobilemcp.pro.automation.AutomatorRegistry
import com.mobilemcp.pro.core.SecurityServices
import com.mobilemcp.pro.security.PairingSecretStore
import com.mobilemcp.pro.server.CommandDispatcher
import com.mobilemcp.pro.server.ControlServer
import com.mobilemcp.pro.server.LogLevel
import com.mobilemcp.pro.server.ServerController
import com.mobilemcp.pro.server.ServerState
import com.mobilemcp.pro.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the control server for as long as it should be running.
 *
 * This is the structural correction to the previous architecture, where `MainActivity`
 * constructed the WebSocket server and this service existed only to post a notification.
 * An Activity is destroyed on every configuration change, so the server died on rotation
 * while the notification kept asserting otherwise. Here the service holds the server, and
 * the UI is free to come and go.
 *
 * `START_REDELIVER_INTENT` rather than `START_STICKY`: if Android restarts the service it
 * must be given the port and bind address it was originally started with. `START_STICKY`
 * redelivers a null intent, which is how the old code would have come back listening on a
 * default port with no way to know it had changed.
 */
class ServerForegroundService : LifecycleService() {

    private var server: ControlServer? = null

    private val serverListener = object : ControlServer.Listener {
        override fun onServerLog(message: String) {
            ServerController.log(LogLevel.INFO, message)
        }

        override fun onClientCountChanged(count: Int) {
            ServerController.updateClientCount(count)
            refreshNotification()
        }

        override fun onServerError(message: String) {
            ServerController.log(LogLevel.ERROR, "Server error: $message")
            ServerController.updateState(ServerState.Failed(message))
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                val bindAddress = intent.getStringExtra(EXTRA_BIND_ADDRESS) ?: DEFAULT_BIND_ADDRESS
                startServer(port, bindAddress)
            }

            else -> {
                // A restart with no usable intent: nothing sensible to listen on.
                Log.w(TAG, "Started without an action; stopping")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun startServer(port: Int, bindAddress: String) {
        if (server != null) {
            Log.i(TAG, "Server already running; ignoring duplicate start")
            return
        }

        ServerController.updateState(ServerState.Starting)

        // Promote to foreground before doing anything slow. Android gives a service a few
        // seconds to call startForeground and kills it with a ForegroundServiceDidNotStartInTime
        // crash otherwise — and binding a port plus unwrapping a Keystore key is exactly the
        // kind of work that can drift past that window on a cold, busy device.
        if (!promoteToForeground(buildNotification(ServerState.Starting))) return

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val secret = PairingSecretStore(this@ServerForegroundService).getOrCreate()
                    ControlServer(
                        bindAddress = bindAddress,
                        port = port,
                        pairingSecret = secret,
                        dispatcher = CommandDispatcher(
                            automatorProvider = AutomatorRegistry::get,
                            appVersion = BuildConfig.VERSION_NAME,
                            // The authorised route to a shell. Passing it here is what makes
                            // the authorisation core reachable at all; without it the shell
                            // commands answer "unsupported" and nothing can elevate.
                            privileged = SecurityServices.privilegedCommands,
                            currentMode = SecurityServices.modeStore::get,
                            tracker = SecurityServices.executionTracker,
                        ),
                        automatorProvider = AutomatorRegistry::get,
                        appVersion = BuildConfig.VERSION_NAME,
                        listener = serverListener,
                    ).also { it.start() }
                }
            }

            outcome
                .onSuccess { started ->
                    server = started
                    ServerController.updateState(
                        ServerState.Running(
                            bindAddress = bindAddress,
                            port = port,
                            connectedClients = 0,
                            secretFingerprint = started.secretFingerprint(),
                        )
                    )
                    ServerController.log(LogLevel.INFO, "Server started on $bindAddress:$port")
                    refreshNotification()
                }
                .onFailure { error ->
                    val reason = when (error) {
                        is java.net.BindException ->
                            "Port $port is already in use. Choose another port."
                        is SecurityException ->
                            "Not permitted to bind $bindAddress:$port"
                        else -> error.message ?: error.javaClass.simpleName
                    }
                    Log.e(TAG, "Failed to start server", error)
                    ServerController.log(LogLevel.ERROR, reason)
                    ServerController.updateState(ServerState.Failed(reason))
                    stopSelf()
                }
        }
    }

    /**
     * Enters the foreground, reporting rather than crashing when Android refuses.
     *
     * Since API 31 a service started from the background throws
     * `ForegroundServiceStartNotAllowedException`, which reaches the user as a crash
     * dialog. It is a legitimate policy outcome — the app was not in the foreground when
     * the start was requested — so it is surfaced as an explanation instead.
     */
    private fun promoteToForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is ForegroundServiceStartNotAllowedException
        ) {
            "Android blocked starting the server from the background. Open DroidPilot and start it from the app."
        } else {
            "Could not start the foreground service: ${e.javaClass.simpleName}"
        }
        Log.e(TAG, reason, e)
        ServerController.log(LogLevel.ERROR, reason)
        ServerController.updateState(ServerState.Failed(reason))
        stopSelf()
        false
    }

    private fun stopServer() {
        server?.shutdown()
        server = null
        ServerController.updateState(ServerState.Stopped)
        ServerController.log(LogLevel.INFO, "Server stopped")
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ------------------------------------------------------------- notification

    private fun refreshNotification() {
        if (server == null) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(ServerController.state.value))
    }

    private fun buildNotification(state: ServerState): Notification {
        val contentText = when (state) {
            is ServerState.Running -> resources.getQuantityString(
                R.plurals.notification_running,
                state.connectedClients,
                state.port,
                state.connectedClients,
            )
            is ServerState.Starting -> getString(R.string.notification_starting)
            is ServerState.Failed -> getString(R.string.notification_failed)
            ServerState.Stopped -> getString(R.string.notification_stopped)
        }

        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ServerForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            // A remote-control server the user cannot stop from the shade is a server they
            // have to go hunting for. It is always one tap away.
            .addAction(0, getString(R.string.action_stop_server), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ServerForegroundService"

        const val CHANNEL_ID = "droidpilot_server"
        const val NOTIFICATION_ID = 1001
        const val DEFAULT_PORT = 8765

        /**
         * Binds every interface by default so the device is reachable over Wi-Fi, Ethernet
         * or a hotspot without the user needing to know which. That breadth is only
         * defensible because authentication is mandatory and enforced during the handshake;
         * the Settings screen offers loopback-only for users tunnelling over `adb forward`.
         */
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
        const val LOOPBACK_BIND_ADDRESS = "127.0.0.1"

        const val ACTION_START = "com.mobilemcp.pro.action.START_SERVER"
        const val ACTION_STOP = "com.mobilemcp.pro.action.STOP_SERVER"
        const val EXTRA_PORT = "port"
        const val EXTRA_BIND_ADDRESS = "bind_address"

        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2

        fun startIntent(context: Context, port: Int, bindAddress: String): Intent =
            Intent(context, ServerForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_BIND_ADDRESS, bindAddress)

        fun stopIntent(context: Context): Intent =
            Intent(context, ServerForegroundService::class.java).setAction(ACTION_STOP)
    }
}
