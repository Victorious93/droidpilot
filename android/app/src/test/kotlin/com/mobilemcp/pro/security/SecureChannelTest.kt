package com.mobilemcp.pro.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SecureChannelTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val salt = ByteArray(16) { (0xA0 + it).toByte() }

    private fun pair(): Pair<SecureChannel, SecureChannel> =
        SecureChannel.derive(secret, salt, isServer = false) to
            SecureChannel.derive(secret, salt, isServer = true)

    @Test
    fun `client and server exchange messages in both directions`() {
        val (client, server) = pair()

        val request = """{"id":"1","command":"ping"}""".toByteArray()
        assertArrayEquals(request, server.open(client.seal(request)))

        val response = """{"id":"1","success":true}""".toByteArray()
        assertArrayEquals(response, client.open(server.seal(response)))
    }

    @Test
    fun `many records in sequence all decrypt`() {
        val (client, server) = pair()
        repeat(500) { i ->
            val payload = "record-$i".toByteArray()
            assertArrayEquals(payload, server.open(client.seal(payload)))
        }
    }

    /**
     * The reason replay protection is not optional here: a record is a device command, so
     * a replayed `tap` is a second real tap. AEAD alone would happily authenticate it.
     */
    @Test
    fun `replaying a record is rejected`() {
        val (client, server) = pair()
        val record = client.seal("""{"id":"1","command":"tap"}""".toByteArray())

        assertNotNull("first delivery should succeed", server.open(record))
        assertNull("replay must be rejected", server.open(record.copyOf()))
    }

    @Test
    fun `records delivered out of order are rejected`() {
        val (client, server) = pair()
        val first = client.seal("first".toByteArray())
        val second = client.seal("second".toByteArray())

        assertNotNull(server.open(second))
        // `first` carries a lower counter than the one already accepted.
        assertNull(server.open(first))
    }

    @Test
    fun `tampering with the ciphertext is detected`() {
        val (client, server) = pair()
        val record = client.seal("sensitive payload".toByteArray())

        val tampered = record.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 0x01).toByte() }
        assertNull(server.open(tampered))
    }

    @Test
    fun `tampering with the authentication tag is detected`() {
        val (client, server) = pair()
        val record = client.seal("payload".toByteArray())

        val tampered = record.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        assertNull(server.open(tampered))
    }

    @Test
    fun `tampering with the nonce is detected`() {
        val (client, server) = pair()
        val record = client.seal("payload".toByteArray())

        // Byte 4 is the first counter byte, so this claims a different record number.
        val tampered = record.copyOf().also { it[4] = (it[4].toInt() xor 0x7F).toByte() }
        assertNull(server.open(tampered))
    }

    @Test
    fun `truncated records are rejected without throwing`() {
        val (client, server) = pair()
        val record = client.seal("payload".toByteArray())

        for (length in 0 until record.size) {
            assertNull("length $length should be rejected", server.open(record.copyOf(length)))
        }
    }

    /**
     * Without separate keys per direction, a record the server sent could be echoed back at
     * it and would authenticate — the server would act on its own response.
     */
    @Test
    fun `a server record reflected back at the server is rejected`() {
        val (_, server) = pair()
        val fromServer = server.seal("""{"id":"1","success":true}""".toByteArray())
        assertNull(server.open(fromServer))
    }

    @Test
    fun `a different pairing secret cannot decrypt the channel`() {
        val client = SecureChannel.derive(secret, salt, isServer = false)
        val wrongSecret = ByteArray(32) { (it + 1).toByte() }
        val eavesdropper = SecureChannel.derive(wrongSecret, salt, isServer = true)

        assertNull(eavesdropper.open(client.seal("payload".toByteArray())))
    }

    @Test
    fun `a different session salt cannot decrypt the channel`() {
        val client = SecureChannel.derive(secret, salt, isServer = false)
        val otherSalt = ByteArray(16) { (0xB0 + it).toByte() }
        val otherSession = SecureChannel.derive(secret, otherSalt, isServer = true)

        assertNull(otherSession.open(client.seal("payload".toByteArray())))
    }

    @Test
    fun `each session produces different ciphertext for identical plaintext`() {
        val plaintext = "identical".toByteArray()

        val first = SecureChannel.derive(secret, SecureChannel.randomSalt(), isServer = false).seal(plaintext)
        val second = SecureChannel.derive(secret, SecureChannel.randomSalt(), isServer = false).seal(plaintext)

        // Same secret, same message, different salts — the records must not be equal, or
        // an observer could tell that the same command was issued twice.
        assertEquals(false, first.contentEquals(second))
    }

    @Test
    fun `empty payloads round-trip`() {
        val (client, server) = pair()
        assertArrayEquals(ByteArray(0), server.open(client.seal(ByteArray(0))))
    }

    @Test
    fun `large payloads round-trip`() {
        val (client, server) = pair()
        // Roughly a full-screen JPEG after base64 — the biggest thing this channel carries.
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        assertArrayEquals(payload, server.open(client.seal(payload)))
    }

    @Test
    fun `derive rejects a wrongly sized secret or salt`() {
        listOf(0, 16, 31, 33, 64).forEach { size ->
            runCatching { SecureChannel.derive(ByteArray(size), salt, isServer = false) }
                .onSuccess { throw AssertionError("secret of $size bytes should have been rejected") }
        }
        listOf(0, 8, 15, 17, 32).forEach { size ->
            runCatching { SecureChannel.derive(secret, ByteArray(size), isServer = false) }
                .onSuccess { throw AssertionError("salt of $size bytes should have been rejected") }
        }
    }

    // ------------------------------------------------------- interoperability

    /**
     * Cross-implementation vector, generated by the Node client in `secure-channel.ts`.
     *
     * The first record of a session is fully determined by (secret, salt, plaintext) —
     * the nonce is the derived prefix with a zero counter — so both implementations can
     * assert the exact same bytes. The Node suite pins this same constant. If either side
     * changes its key schedule, nonce layout or record framing, this test fails here
     * instead of the two silently failing to talk to each other on a user's device.
     */
    @Test
    fun `matches the vector produced by the Node client`() {
        val expected = (
            "9f4eb382000000000000000094e5abbff9488314ea531144d012dde44179a648" +
                "e7686d9c0522ad43b7f295cd765d7ef498025e74885f471ea21ddfb336bf"
            ).hexToBytes()
        val plaintext = """{"id":"vector-1","command":"ping"}""".toByteArray()

        val client = SecureChannel.derive(secret, salt, isServer = false)
        assertArrayEquals(
            "Kotlin must produce byte-identical records to the Node client",
            expected,
            client.seal(plaintext),
        )

        val server = SecureChannel.derive(secret, salt, isServer = true)
        assertArrayEquals(
            "Kotlin must decrypt records produced by the Node client",
            plaintext,
            server.open(expected),
        )
    }

    /**
     * RFC 5869 Appendix A, Test Case 1.
     *
     * Verifying the key schedule against the standard's own vector, rather than only
     * against the other implementation, is what distinguishes "these two agree" from
     * "these two are both correct".
     */
    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0B }
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()

        val expected = (
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
            ).hexToBytes()

        assertArrayEquals(expected, SecureChannel.hkdf(ikm, salt, info, 42))
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
