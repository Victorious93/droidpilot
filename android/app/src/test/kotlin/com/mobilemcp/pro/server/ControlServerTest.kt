package com.mobilemcp.pro.server

import com.mobilemcp.pro.automation.FakeDeviceAutomator
import com.mobilemcp.pro.protocol.CommandResponse
import com.mobilemcp.pro.protocol.Protocol
import com.mobilemcp.pro.protocol.ServerHello
import com.mobilemcp.pro.security.PairingSecret
import com.mobilemcp.pro.security.SecureChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests for the real [ControlServer], over a real loopback socket.
 *
 * The server is plain JVM code — Java-WebSocket, kotlinx.serialization and JCA — so the
 * whole connection lifecycle can be exercised here without a device: the handshake,
 * authentication, key agreement, and the encrypted command loop. That matters because
 * authentication is the single most important behaviour in this codebase and the previous
 * implementation's version of it was never exercised at all.
 *
 * Only [ControlServer]'s collaborators are substituted: a [FakeDeviceAutomator] stands in
 * for the Accessibility service. The transport, crypto and protocol code under test is the
 * same code that ships.
 */
class ControlServerTest {

    private val secret = ByteArray(32) { (it * 3 + 1).toByte() }
    private lateinit var server: ControlServer
    private lateinit var automator: FakeDeviceAutomator
    private var port: Int = 0

    private val serverLogs = ConcurrentLinkedQueue<String>()

    @Before
    fun startServer() {
        automator = FakeDeviceAutomator()
        val started = CountDownLatch(1)

        server = ControlServer(
            bindAddress = "127.0.0.1",
            port = 0, // Ephemeral: lets tests run in parallel and on busy machines.
            pairingSecret = secret,
            dispatcher = CommandDispatcher({ automator }, appVersion = "2.0.0-test"),
            automatorProvider = { automator },
            appVersion = "2.0.0-test",
            listener = object : ControlServer.Listener {
                override fun onServerLog(message: String) {
                    serverLogs += message
                    if (message.startsWith("Listening on")) started.countDown()
                }

                override fun onClientCountChanged(count: Int) = Unit
                override fun onServerError(message: String) {
                    serverLogs += "ERROR: $message"
                    started.countDown()
                }
            },
        )
        server.start()

        assertTrue("server did not start", started.await(20, TimeUnit.SECONDS))
        port = server.port
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    // ------------------------------------------------------------------- client

    /** A minimal client speaking the real protocol, mirroring the Node implementation. */
    private inner class TestClient(presentedSecret: String?) : WebSocketClient(URI("ws://127.0.0.1:$port")) {

        val helloReceived = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val responses = ConcurrentLinkedQueue<CommandResponse>()

        @Volatile var channel: SecureChannel? = null
        @Volatile var hello: ServerHello? = null
        @Volatile var closeCode: Int = 0
        @Volatile var failed: String? = null

        init {
            presentedSecret?.let { addHeader("Authorization", "Bearer $it") }
        }

        override fun onOpen(handshakedata: ServerHandshake) = Unit

        override fun onMessage(message: String) {
            // The session salt is the only plaintext frame in the protocol.
            val salt = Base64.getDecoder().decode(
                Protocol.json.parseToJsonElement(message).jsonObject["salt"]!!.jsonPrimitive.content
            )
            channel = SecureChannel.derive(secret, salt, isServer = false)
        }

        override fun onMessage(bytes: ByteBuffer) {
            val record = ByteArray(bytes.remaining()).also { bytes.get(it) }
            val plaintext = channel?.open(record) ?: run {
                failed = "could not decrypt a frame"
                helloReceived.countDown()
                return
            }

            val text = plaintext.decodeToString()
            if (Protocol.json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content == "hello") {
                hello = Protocol.json.decodeFromString(ServerHello.serializer(), text)
                helloReceived.countDown()
            } else {
                responses += Protocol.json.decodeFromString(CommandResponse.serializer(), text)
            }
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            closeCode = code
            helloReceived.countDown()
            closed.countDown()
        }

        override fun onError(ex: Exception) {
            failed = ex.message
            helloReceived.countDown()
            closed.countDown()
        }

        fun sendCommand(id: String, command: String, params: String = "{}") {
            val payload = """{"id":"$id","command":"$command","params":$params}"""
            send(channel!!.seal(payload.encodeToByteArray()))
        }

        /**
         * Deliberately generous. These are real sockets and real coroutine dispatch, and CI
         * runners are slower and far more contended than a developer machine — a budget
         * that only just suffices here becomes an intermittently red master there. Raising
         * the ceiling costs nothing when the test passes and weakens no assertion; the test
         * still fails if the response never arrives.
         */
        fun awaitResponse(timeoutMillis: Long = 20_000): CommandResponse? {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                responses.poll()?.let { return it }
                Thread.sleep(20)
            }
            return null
        }
    }

    private fun connectedClient(): TestClient =
        TestClient(PairingSecret.encode(secret)).also {
            it.connectBlocking(20, TimeUnit.SECONDS)
            assertTrue("handshake did not complete", it.helloReceived.await(20, TimeUnit.SECONDS))
        }

    // -------------------------------------------------------------- happy path

    @Test
    fun `a correctly paired client completes the handshake and receives the hello`() {
        val client = connectedClient()
        try {
            assertNotNull(client.hello)
            val hello = client.hello!!
            assertEquals("hello", hello.type)
            assertEquals(Protocol.VERSION, hello.protocolVersion)
            assertTrue("the channel must be reported as encrypted", hello.encrypted)
            assertEquals("2.0.0-test", hello.appVersion)
            assertEquals(automator.capabilityNames(), hello.capabilities)
        } finally {
            client.closeBlocking()
        }
    }

    @Test
    fun `commands round-trip over the encrypted channel`() {
        val client = connectedClient()
        try {
            client.sendCommand("c1", "tap", """{"x":540,"y":1200}""")
            val tap = client.awaitResponse()
            assertNotNull(tap)
            assertTrue(tap!!.success)
            assertEquals("c1", tap.id)

            client.sendCommand("c2", "get_device_info")
            val info = client.awaitResponse()
            assertNotNull(info)
            assertTrue(info!!.success)
            assertEquals("c2", info.id)
            assertEquals("Fake", info.data!!.jsonObject["manufacturer"]!!.jsonPrimitive.content)
        } finally {
            client.closeBlocking()
        }
    }

    @Test
    fun `a command failure crosses the wire with its error code`() {
        val client = connectedClient()
        try {
            // No selector criteria: the dispatcher must refuse rather than click the root.
            client.sendCommand("c1", "click_element")

            val response = client.awaitResponse()
            assertNotNull(response)
            assertFalse(response!!.success)
            assertEquals(com.mobilemcp.pro.core.ErrorCode.INVALID_REQUEST, response.errorCode)
        } finally {
            client.closeBlocking()
        }
    }

    @Test
    fun `ping is answered even though it needs no device`() {
        val client = connectedClient()
        try {
            client.sendCommand("c1", "ping")
            val response = client.awaitResponse()

            assertNotNull(response)
            assertTrue(response!!.success)
            assertEquals(
                Protocol.VERSION.toString(),
                response.data!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content,
            )
        } finally {
            client.closeBlocking()
        }
    }

    // ----------------------------------------------------------- authentication

    /**
     * The central security property, tested against the real server.
     *
     * The connection must be refused during the HTTP upgrade, so the peer never reaches a
     * state in which it could send a command. The previous implementation checked the token
     * in `onOpen` — after the WebSocket was already established, where `close()` is
     * asynchronous and buffered frames still reached the handler. It also never assigned the
     * token, so the check could not have fired in any case.
     */
    @Test
    fun `a client presenting the wrong secret never establishes a session`() {
        val wrong = PairingSecret.encode(ByteArray(32) { 0x42 })
        val client = TestClient(wrong)

        client.connectBlocking(20, TimeUnit.SECONDS)
        assertTrue("client should have been dropped", client.closed.await(20, TimeUnit.SECONDS))

        assertNull("no session key may be derived", client.channel)
        assertNull("no hello may be delivered", client.hello)
        assertEquals(0, server.connectedClients)
    }

    @Test
    fun `a client presenting no secret at all is refused`() {
        val client = TestClient(null)

        client.connectBlocking(20, TimeUnit.SECONDS)
        assertTrue(client.closed.await(20, TimeUnit.SECONDS))

        assertNull(client.channel)
        assertNull(client.hello)
        assertEquals(0, server.connectedClients)
    }

    @Test
    fun `a malformed authorization header is refused`() {
        val client = TestClient("this is not base64 at all !!!")

        client.connectBlocking(20, TimeUnit.SECONDS)
        assertTrue(client.closed.await(20, TimeUnit.SECONDS))
        assertNull(client.hello)
    }

    @Test
    fun `repeated failures are logged and eventually locked out`() {
        repeat(6) {
            TestClient(PairingSecret.encode(ByteArray(32) { 0x7A })).apply {
                connectBlocking(20, TimeUnit.SECONDS)
                closed.await(20, TimeUnit.SECONDS)
            }
        }

        assertTrue(
            "the server should report a lockout after repeated failures",
            serverLogs.any { it.contains("Locked out") },
        )
    }

    // ------------------------------------------------------------ channel abuse

    /**
     * Accepting a text frame after the session is established would be a downgrade path: an
     * attacker unable to produce a valid ciphertext could bypass the channel with plain JSON.
     */
    @Test
    fun `an unencrypted frame sent after the handshake drops the connection`() {
        val client = connectedClient()

        client.send("""{"id":"x","command":"tap","params":{"x":1,"y":1}}""")

        assertTrue("connection should have been closed", client.closed.await(20, TimeUnit.SECONDS))
        assertTrue(serverLogs.any { it.contains("unencrypted frame") })
    }

    @Test
    fun `a replayed record drops the connection`() {
        val client = connectedClient()

        val record = client.channel!!.seal("""{"id":"r1","command":"ping","params":{}}""".encodeToByteArray())
        client.send(record)
        assertNotNull("the original must be accepted", client.awaitResponse())

        client.send(record) // Byte-identical replay.

        assertTrue("connection should have been closed", client.closed.await(20, TimeUnit.SECONDS))
        assertTrue(serverLogs.any { it.contains("failed authentication or replayed") })
    }

    @Test
    fun `a forged record drops the connection`() {
        val client = connectedClient()

        // Correct length and structure, but not produced with the session key.
        client.send(ByteArray(64) { 0xEE.toByte() })

        assertTrue(client.closed.await(20, TimeUnit.SECONDS))
    }

    @Test
    fun `a malformed request inside a valid record is answered, not fatal`() {
        val client = connectedClient()
        try {
            // Authenticates correctly but is not a CommandRequest. The channel is intact, so
            // this must be answered with an error rather than treated as an attack.
            client.send(client.channel!!.seal("""{"nonsense":true}""".encodeToByteArray()))

            val response = client.awaitResponse()
            assertNotNull(response)
            assertFalse(response!!.success)
            assertEquals(com.mobilemcp.pro.core.ErrorCode.INVALID_REQUEST, response.errorCode)
        } finally {
            client.closeBlocking()
        }
    }

    // ------------------------------------------------------------- concurrency

    /**
     * The regression test for the executor starvation this replaced.
     *
     * The previous server dispatched onto a fixed pool of four threads and let
     * `wait_for_element` block one with `Thread.sleep` for its whole timeout. Four
     * concurrent waits wedged the server completely — `ping` included. Here the waits
     * suspend, so an unrelated command is still answered while several are outstanding.
     */
    @Test
    fun `long waits do not block unrelated commands`() {
        val client = connectedClient()
        try {
            repeat(6) { i ->
                client.sendCommand("wait-$i", "wait_for_element", """{"text":"never","timeout":30000}""")
            }

            client.sendCommand("ping-1", "ping")

            // The ping must come back while the waits are still outstanding.
            val deadline = System.currentTimeMillis() + 25_000
            var pong: CommandResponse? = null
            while (System.currentTimeMillis() < deadline && pong == null) {
                pong = client.responses.poll()?.takeIf { it.id == "ping-1" }
                Thread.sleep(20)
            }

            assertNotNull("ping must be answered while long waits are in flight", pong)
            assertTrue(pong!!.success)
        } finally {
            client.closeBlocking()
        }
    }

    @Test
    fun `the secret fingerprint is exposed without exposing the secret`() {
        val fingerprint = server.secretFingerprint()

        assertEquals(8, fingerprint.length)
        assertFalse(fingerprint.contains(PairingSecret.encode(secret)))
    }

}
