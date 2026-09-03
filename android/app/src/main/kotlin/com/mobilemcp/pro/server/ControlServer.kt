package com.mobilemcp.pro.server

import android.util.Log
import com.mobilemcp.pro.core.ErrorCode
import com.mobilemcp.pro.protocol.CommandRequest
import com.mobilemcp.pro.protocol.CommandResponse
import com.mobilemcp.pro.protocol.Protocol
import com.mobilemcp.pro.protocol.ServerHello
import com.mobilemcp.pro.security.AuthGate
import com.mobilemcp.pro.security.PairingSecret
import com.mobilemcp.pro.security.SecureChannel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.Protocol as WebSocketSubprotocol
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The authenticated, encrypted control endpoint.
 *
 * ## Connection lifecycle
 *
 * 1. The client presents the pairing secret in an `Authorization: Bearer` header.
 * 2. **The server validates it while building the handshake response.** This is the
 *    correction of the most serious defect in the previous implementation, which checked
 *    the token in `onOpen` — after the WebSocket had already been established. `close()`
 *    is asynchronous, so frames the client had already sent were queued and delivered to
 *    the message handler regardless of the verdict. Rejecting during the handshake means
 *    an unauthenticated peer never reaches a state where it can send a command at all.
 *    (In that build the check was unreachable anyway: `authToken` was never assigned, so
 *    every server ran wide open.)
 * 3. The server returns a fresh 16-byte session salt in one plaintext frame.
 * 4. Both sides derive session keys from `HKDF(secret, salt)`; every later frame is
 *    AES-256-GCM. See [SecureChannel].
 * 5. The server sends an encrypted `hello` describing protocol version and capabilities.
 *
 * ## Resource bounds
 *
 * Concurrent clients, in-flight commands and inbound frame size are all capped. This is a
 * server listening on a phone's network interface: without limits, a handful of
 * connections can exhaust its memory, and unlike a desktop the failure takes the user's
 * whole device down with it.
 */
class ControlServer(
    bindAddress: String,
    port: Int,
    private val pairingSecret: ByteArray,
    private val dispatcher: CommandDispatcher,
    private val automatorProvider: () -> com.mobilemcp.pro.automation.DeviceAutomator?,
    private val appVersion: String,
    private val listener: Listener,
) : WebSocketServer(
    InetSocketAddress(bindAddress, port),
    DECODER_THREADS,
    // A custom draft, purely to cap inbound frame size. The protocol list must contain
    // `Protocol("")` — the empty name meaning "no subprotocol required", which is what the
    // default `Draft_6455()` uses. With an empty *list*, `containsRequestedProtocol` has
    // nothing to iterate and returns NOT_MATCHED, so the server rejects every client
    // regardless of credentials. Covered by ControlServerTest.
    listOf(Draft_6455(emptyList(), listOf(WebSocketSubprotocol("")), MAX_INBOUND_FRAME_BYTES)),
) {

    /** Callbacks for the UI layer. Always invoked off the main thread. */
    interface Listener {
        fun onServerLog(message: String)
        fun onClientCountChanged(count: Int)
        fun onServerError(message: String)
    }

    private class Session(val channel: SecureChannel, val peer: String) {
        /**
         * Serialises sealing a record with writing it to the socket.
         *
         * [SecureChannel.seal] is already synchronised, so two concurrent seals cannot
         * reuse a nonce — but taking the counter and enqueuing the frame were separate
         * steps, and up to [MAX_CONCURRENT_COMMANDS] command coroutines finish at once.
         * Two responses could therefore be sealed as counters n and n+1 and reach the
         * socket in the opposite order. The peer requires *strictly increasing* counters
         * — that is what makes a replayed command impossible — so it drops the record
         * that arrives late, and a client silently never receives one of its answers.
         *
         * Holding this across both steps makes the order records are numbered in the same
         * as the order they are written in.
         */
        val sendLock = Any()
    }

    private val sessions = ConcurrentHashMap<WebSocket, Session>()
    private val authGate = AuthGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("droidpilot-server"))

    /**
     * Bounds how many commands run at once across all clients.
     *
     * Note that this is a *ceiling*, not a thread pool: a command waiting on a gesture or
     * a UI poll is suspended, not parked on a thread. That is what fixes the previous
     * design, where four concurrent `wait_for_element` calls filled a four-thread executor
     * with sleeping threads and stalled every other request — `ping` included.
     */
    private val inFlight = Semaphore(MAX_CONCURRENT_COMMANDS)

    // ---------------------------------------------------------------- handshake

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: org.java_websocket.drafts.Draft,
        request: ClientHandshake,
    ): ServerHandshakeBuilder {
        val builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)

        if (sessions.size >= MAX_CLIENTS) {
            throw InvalidDataException(CloseFrame.TRY_AGAIN_LATER, "Too many connected clients")
        }

        // Identify by host, not host:port — otherwise an attacker resets their own rate
        // limit budget simply by opening a new socket from a new ephemeral port.
        val peerHost = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"

        val presented = request.getFieldValue(HEADER_AUTHORIZATION)
            .takeIf { it.isNotBlank() }
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()

        when (val decision = authGate.evaluate(peerHost, pairingSecret, presented)) {
            is AuthGate.Decision.Allowed -> Unit

            is AuthGate.Decision.Rejected -> {
                listener.onServerLog("Rejected $peerHost: ${decision.reason}")
                throw InvalidDataException(CloseFrame.POLICY_VALIDATION, decision.reason)
            }

            is AuthGate.Decision.LockedOut -> {
                val seconds = decision.retryAfterMillis / 1000
                listener.onServerLog("Locked out $peerHost for ${seconds}s after repeated failures")
                throw InvalidDataException(
                    CloseFrame.POLICY_VALIDATION,
                    "Too many failed attempts; retry in ${seconds}s",
                )
            }
        }

        return builder
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val peer = conn.remoteSocketAddress?.address?.hostAddress ?: "unknown"
        val salt = SecureChannel.randomSalt()
        val channel = SecureChannel.derive(pairingSecret, salt, isServer = true)
        sessions[conn] = Session(channel, peer)

        // The salt is the one frame sent in the clear. It is a public nonce by design —
        // its job is to make the session keys unique, not to be secret.
        conn.send(
            Protocol.json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("type", "session")
                    put("salt", Base64.getEncoder().encodeToString(salt))
                    put("protocolVersion", Protocol.VERSION)
                },
            )
        )

        val hello = ServerHello(
            appVersion = appVersion,
            encrypted = true,
            capabilities = automatorProvider()?.capabilityNames() ?: emptyList(),
        )
        sendEncrypted(conn, Protocol.json.encodeToString(ServerHello.serializer(), hello))

        listener.onServerLog("Client connected: $peer")
        listener.onClientCountChanged(sessions.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val session = sessions.remove(conn)
        listener.onServerLog("Client disconnected: ${session?.peer ?: "unknown"} ($code)")
        listener.onClientCountChanged(sessions.size)
    }

    // ----------------------------------------------------------------- messages

    /**
     * Rejects plaintext frames outright.
     *
     * Once the session is established every frame must be encrypted. Accepting a text
     * frame here would be a downgrade path: an attacker who cannot produce a valid
     * ciphertext could otherwise skip the channel entirely by sending plain JSON.
     */
    override fun onMessage(conn: WebSocket, message: String) {
        listener.onServerLog("Dropped an unencrypted frame from ${sessions[conn]?.peer}")
        conn.close(CloseFrame.POLICY_VALIDATION, "Encrypted frames required")
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val session = sessions[conn] ?: run {
            conn.close(CloseFrame.POLICY_VALIDATION, "No session")
            return
        }

        val record = ByteArray(message.remaining()).also { message.get(it) }
        val plaintext = session.channel.open(record)
        if (plaintext == null) {
            // Authentication failure, or a replayed counter. Either means the stream can no
            // longer be trusted, so the connection goes rather than the frame.
            listener.onServerLog("Dropping ${session.peer}: frame failed authentication or replayed")
            conn.close(CloseFrame.POLICY_VALIDATION, "Record authentication failed")
            return
        }

        val request = try {
            Protocol.json.decodeFromString(CommandRequest.serializer(), plaintext.decodeToString())
        } catch (e: SerializationException) {
            // Structured rejection, and the request id is unknown by definition here.
            sendEncrypted(
                conn,
                Protocol.json.encodeToString(
                    CommandResponse.serializer(),
                    CommandResponse.error("unknown", ErrorCode.INVALID_REQUEST, "Malformed request frame"),
                ),
            )
            return
        }

        scope.launch {
            inFlight.withPermit {
                listener.onServerLog("→ ${request.command}")
                val response = dispatcher.dispatch(request)
                listener.onServerLog("← ${request.command}: ${if (response.success) "ok" else response.errorCode?.name}")
                if (conn.isOpen) {
                    sendEncrypted(conn, Protocol.json.encodeToString(CommandResponse.serializer(), response))
                }
            }
        }
    }

    private fun sendEncrypted(conn: WebSocket, payload: String) {
        val session = sessions[conn] ?: return
        try {
            synchronized(session.sendLock) {
                conn.send(session.channel.seal(payload.encodeToByteArray()))
            }
        } catch (e: Exception) {
            // A send can fail benignly when the peer vanishes mid-response; not worth
            // tearing anything down beyond this connection.
            Log.d(TAG, "Send to ${session.peer} failed: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ control

    override fun onStart() {
        connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SECONDS
        listener.onServerLog("Listening on ${address.hostString}:${port}")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) {
            // A null connection means the listening socket itself failed — the port is
            // taken, or the address is not assignable. That is fatal for the server.
            Log.e(TAG, "Server socket error", ex)
            listener.onServerError(ex.message ?: ex.javaClass.simpleName)
        } else {
            Log.d(TAG, "Connection error from ${sessions[conn]?.peer}", ex)
        }
    }

    /** Stops accepting connections, cancels in-flight work and releases the port. */
    fun shutdown() {
        scope.cancel()
        sessions.clear()
        try {
            stop(STOP_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            Log.w(TAG, "Unclean shutdown: ${e.javaClass.simpleName}")
        }
    }

    val connectedClients: Int get() = sessions.size

    /** Fingerprint of the secret in use, for display. Never the secret itself. */
    fun secretFingerprint(): String = PairingSecret.fingerprint(pairingSecret)

    private companion object {
        const val TAG = "ControlServer"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val DECODER_THREADS = 2
        const val MAX_CLIENTS = 4
        const val MAX_CONCURRENT_COMMANDS = 8
        const val MAX_INBOUND_FRAME_BYTES = 1 shl 20 // 1 MiB; commands are tiny
        const val CONNECTION_LOST_TIMEOUT_SECONDS = 60
        const val STOP_TIMEOUT_MILLIS = 1_000
    }
}
