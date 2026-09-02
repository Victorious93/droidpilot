package com.mobilemcp.pro.protocol

import com.mobilemcp.pro.core.ErrorCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private fun decode(json: String) =
        Protocol.json.decodeFromString(CommandRequest.serializer(), json)

    @Test
    fun `a well-formed request decodes`() {
        val request = decode("""{"id":"1","command":"tap","params":{"x":10,"y":20}}""")

        assertEquals("1", request.id)
        assertEquals("tap", request.command)
        assertEquals("10", request.params["x"]!!.jsonPrimitive.content)
    }

    @Test
    fun `params defaults to an empty object when omitted`() {
        assertTrue(decode("""{"id":"1","command":"ping"}""").params.isEmpty())
    }

    /**
     * The central reason for moving off Gson.
     *
     * Gson constructs objects with `sun.misc.Unsafe` and assigns fields reflectively, so a
     * request missing `command` produced a `CommandRequest` whose non-null `String` field
     * held `null` — Kotlin's type system said it could not happen, and the eventual NPE
     * surfaced far from the malformed input that caused it. kotlinx.serialization rejects
     * it at the edge.
     */
    @Test
    fun `a request missing a required field is rejected at decode time`() {
        listOf(
            """{"id":"1"}""",
            """{"command":"tap"}""",
            """{}""",
        ).forEach { malformed ->
            runCatching { decode(malformed) }
                .onSuccess { throw AssertionError("'$malformed' should not have decoded") }
                .onFailure { assertTrue(it is SerializationException) }
        }
    }

    @Test
    fun `an explicit null in a required field is rejected`() {
        runCatching { decode("""{"id":"1","command":null}""") }
            .onSuccess { throw AssertionError("null command should not have decoded") }
            .onFailure { assertTrue(it is SerializationException) }
    }

    @Test
    fun `unknown fields are ignored so newer clients stay compatible`() {
        val request = decode("""{"id":"1","command":"ping","futureField":true,"params":{}}""")
        assertEquals("ping", request.command)
    }

    @Test
    fun `malformed json raises a serialization exception rather than escaping`() {
        listOf("", "not json", "{", "[]", "null")
            .forEach { malformed ->
                runCatching { decode(malformed) }
                    .onSuccess { throw AssertionError("'$malformed' should not have decoded") }
            }
    }

    @Test
    fun `a success response round-trips`() {
        val response = CommandResponse.success("1", buildJsonObject { put("result", "ok") })

        val encoded = Protocol.json.encodeToString(CommandResponse.serializer(), response)
        val decoded = Protocol.json.decodeFromString(CommandResponse.serializer(), encoded)

        assertTrue(decoded.success)
        assertEquals("1", decoded.id)
        assertNull(decoded.error)
    }

    @Test
    fun `an error response carries a machine-readable code`() {
        val response = CommandResponse.error("1", ErrorCode.NOT_FOUND, "No element matched")
        val encoded = Protocol.json.encodeToString(CommandResponse.serializer(), response)

        // The wire key is snake_case and the value is the enum name; both are API, so a
        // rename would be a breaking change and this pins them.
        assertTrue(encoded.contains(""""error_code":"NOT_FOUND""""))

        val decoded = Protocol.json.decodeFromString(CommandResponse.serializer(), encoded)
        assertFalse(decoded.success)
        assertEquals(ErrorCode.NOT_FOUND, decoded.errorCode)
    }

    @Test
    fun `every error code survives a round trip`() {
        ErrorCode.entries.forEach { code ->
            val encoded = Protocol.json.encodeToString(
                CommandResponse.serializer(),
                CommandResponse.error("1", code, "message"),
            )
            assertEquals(
                code,
                Protocol.json.decodeFromString(CommandResponse.serializer(), encoded).errorCode,
            )
        }
    }

    @Test
    fun `the server hello advertises version and capabilities`() {
        val hello = ServerHello(
            appVersion = "2.0.0",
            encrypted = true,
            capabilities = listOf("accessibility", "gestures"),
        )
        val encoded = Protocol.json.encodeToString(ServerHello.serializer(), hello)
        val decoded = Protocol.json.decodeFromString(ServerHello.serializer(), encoded)

        assertEquals("hello", decoded.type)
        assertEquals(Protocol.VERSION, decoded.protocolVersion)
        assertTrue(decoded.encrypted)
        assertEquals(2, decoded.capabilities.size)
    }

    @Test
    fun `null fields are omitted from the encoded form`() {
        val encoded = Protocol.json.encodeToString(
            CommandResponse.serializer(),
            CommandResponse.success("1", kotlinx.serialization.json.JsonNull),
        )
        // Screenshot payloads make responses large; there is no reason to spend bytes
        // transmitting keys that are null.
        assertFalse(encoded.contains(""""error":"""))
        assertNotNull(encoded)
    }
}
