package com.mobilemcp.pro

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilemcp.pro.automation.DeviceAutomator
import com.mobilemcp.pro.protocol.CommandResponse
import com.mobilemcp.pro.protocol.Protocol
import com.mobilemcp.pro.protocol.ServerHello
import com.mobilemcp.pro.security.PairingSecret
import com.mobilemcp.pro.security.SecureChannel
import com.mobilemcp.pro.server.CommandDispatcher
import com.mobilemcp.pro.server.ControlServer
import com.mobilemcp.pro.ui.MainActivity
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
import org.junit.runner.RunWith
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The whole stack, on a real device: control server, authentication, encrypted channel,
 * command dispatch, and a genuine Accessibility service behind it.
 *
 * `ControlServerTest` in the JVM suite covers the same lifecycle against a fake automator,
 * which is what makes it fast enough to run on every change. This one closes the remaining
 * question that fake cannot answer: that the same protocol still works when the thing
 * behind the dispatcher is Android rather than a stand-in — and that a command sent over
 * the wire comes back carrying real screen data.
 *
 * Bound to loopback, so nothing is exposed to the network the test host sits on.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndInstrumentedTest {

    private val secret = ByteArray(32) { (it * 5 + 3).toByte() }

    private lateinit var automator: DeviceAutomator
    private lateinit var server: ControlServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private var port: Int = 0

    @Before
    fun startServer() {
        automator = AccessibilityServiceHarness.enableAndAwait()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(1_000)

        val started = CountDownLatch(1)
        server = ControlServer(
            bindAddress = "127.0.0.1",
            port = 0,
            pairingSecret = secret,
            dispatcher = CommandDispatcher({ automator }, appVersion = "instrumented"),
            automatorProvider = { automator },
            appVersion = "instrumented",
            listener = object : ControlServer.Listener {
                override fun onServerLog(message: String) {
                    if (message.startsWith("Listening on")) started.countDown()
                }

                override fun onClientCountChanged(count: Int) = Unit
                override fun onServerError(message: String) = started.countDown()
            },
        )
        server.start()
        assertTrue("server did not start", started.await(15, TimeUnit.SECONDS))
        port = server.port
    }

    @After
    fun stopServer() {
        server.shutdown()
        scenario?.close()
    }

    private inner class TestClient(presented: String?) : WebSocketClient(URI("ws://127.0.0.1:$port")) {
        val helloReceived = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val responses = ConcurrentLinkedQueue<CommandResponse>()

        @Volatile var channel: SecureChannel? = null
        @Volatile var hello: ServerHello? = null

        init {
            presented?.let { addHeader("Authorization", "Bearer $it") }
        }

        override fun onOpen(handshakedata: ServerHandshake) = Unit

        override fun onMessage(message: String) {
            val salt = Base64.getDecoder().decode(
                Protocol.json.parseToJsonElement(message).jsonObject["salt"]!!.jsonPrimitive.content
            )
            channel = SecureChannel.derive(secret, salt, isServer = false)
        }

        override fun onMessage(bytes: ByteBuffer) {
            val record = ByteArray(bytes.remaining()).also { bytes.get(it) }
            val plaintext = channel?.open(record) ?: return
            val text = plaintext.decodeToString()

            if (Protocol.json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content == "hello") {
                hello = Protocol.json.decodeFromString(ServerHello.serializer(), text)
                helloReceived.countDown()
            } else {
                responses += Protocol.json.decodeFromString(CommandResponse.serializer(), text)
            }
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            helloReceived.countDown()
            closed.countDown()
        }

        override fun onError(ex: Exception) {
            helloReceived.countDown()
            closed.countDown()
        }

        fun send(id: String, command: String, params: String = "{}") {
            send(channel!!.seal("""{"id":"$id","command":"$command","params":$params}""".encodeToByteArray()))
        }

        fun await(timeoutMillis: Long = 20_000): CommandResponse? {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                responses.poll()?.let { return it }
                Thread.sleep(25)
            }
            return null
        }
    }

    private fun connect(): TestClient = TestClient(PairingSecret.encode(secret)).also {
        it.connectBlocking(15, TimeUnit.SECONDS)
        assertTrue("handshake did not complete", it.helloReceived.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun completesTheHandshakeAndAdvertisesRealCapabilities() {
        val client = connect()
        try {
            val hello = client.hello
            assertNotNull("no hello received", hello)
            assertTrue(hello!!.encrypted)
            assertEquals(Protocol.VERSION, hello.protocolVersion)
            assertTrue(
                "capabilities should come from the live service",
                "accessibility" in hello.capabilities,
            )
        } finally {
            client.closeBlocking()
        }
    }

    /**
     * The point of the whole exercise: a command crosses an authenticated, encrypted socket
     * and comes back carrying data read from the actual screen.
     */
    @Test
    fun aCommandOverTheWireReturnsRealScreenData() {
        val client = connect()
        try {
            client.send("t1", "get_ui_tree", """{"maxDepth":15,"maxNodes":2000}""")
            val response = client.await()

            assertNotNull("no response to get_ui_tree", response)
            assertTrue("get_ui_tree failed: ${response!!.error}", response.success)

            val data = response.data!!.jsonObject
            val nodeCount = data["nodeCount"]!!.jsonPrimitive.content.toInt()
            assertTrue("a live screen should yield more than one node", nodeCount > 1)
            assertNotNull(data["tree"])
        } finally {
            client.closeBlocking()
        }
    }

    @Test
    fun findElementOverTheWireLocatesRealUi() {
        val client = connect()
        try {
            client.send("t1", "find_element", """{"text":"DroidPilot","maxResults":5}""")
            val response = client.await()

            assertNotNull(response)
            assertTrue("find_element failed: ${response!!.error}", response.success)
            assertTrue(
                "the app title should be findable on its own screen",
                response.data!!.jsonObject["count"]!!.jsonPrimitive.content.toInt() > 0,
            )
        } finally {
            client.closeBlocking()
        }
    }

    @Test
    fun screenshotOverTheWireReturnsAnImage() {
        val client = connect()
        try {
            client.send("t1", "screenshot", """{"quality":60,"maxDimension":800}""")
            val response = client.await()

            assertNotNull(response)
            assertTrue("screenshot failed: ${response!!.error}", response.success)

            val image = response.data!!.jsonObject["image"]!!.jsonPrimitive.content
            assertTrue("expected a non-trivial image payload", image.length > 1_000)
        } finally {
            client.closeBlocking()
        }
    }

    /**
     * The security property, verified end to end on a real device rather than only against
     * the JVM harness: a wrong secret is refused during the HTTP upgrade, so the peer never
     * reaches a state in which a command could be sent.
     */
    @Test
    fun aClientWithTheWrongSecretIsRefused() {
        val client = TestClient(PairingSecret.encode(ByteArray(32) { 0x5A }))

        client.connectBlocking(15, TimeUnit.SECONDS)
        assertTrue("the client should have been dropped", client.closed.await(15, TimeUnit.SECONDS))

        assertNull("no session key may be derived", client.channel)
        assertNull("no hello may be delivered", client.hello)
        assertEquals(0, server.connectedClients)
    }

    @Test
    fun aClientWithNoSecretIsRefused() {
        val client = TestClient(null)

        client.connectBlocking(15, TimeUnit.SECONDS)
        assertTrue(client.closed.await(15, TimeUnit.SECONDS))

        assertNull(client.hello)
        assertEquals(0, server.connectedClients)
    }

    @Test
    fun anUnencryptedFrameAfterTheHandshakeDropsTheConnection() {
        val client = connect()

        client.send("""{"id":"x","command":"ping","params":{}}""")

        assertTrue("plaintext must not be accepted", client.closed.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun aReplayedRecordDropsTheConnection() {
        val client = connect()

        val record = client.channel!!.seal("""{"id":"r1","command":"ping","params":{}}""".encodeToByteArray())
        client.send(record)
        assertNotNull("the original must be accepted", client.await())

        client.send(record)

        assertTrue("a replayed command must not be executed", client.closed.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun deviceInfoOverTheWireDescribesTheRealDevice() {
        val client = connect()
        try {
            client.send("t1", "get_device_info")
            val response = client.await()

            assertNotNull(response)
            assertTrue(response!!.success)

            val data = response.data!!.jsonObject
            assertTrue(data["screenWidth"]!!.jsonPrimitive.content.toInt() > 0)
            assertTrue(data["sdkInt"]!!.jsonPrimitive.content.toInt() >= 30)
            assertFalse(data["manufacturer"]!!.jsonPrimitive.content.isBlank())
        } finally {
            client.closeBlocking()
        }
    }
}
