package com.mobilemcp.pro.protocol

import com.mobilemcp.pro.core.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire protocol shared with the Node MCP server.
 *
 * This replaces the previous Gson-based models. Gson constructs objects through
 * `sun.misc.Unsafe`, which bypasses Kotlin's null-safety entirely: a field declared
 * `val command: String` could legitimately hold `null` at runtime if the peer omitted it,
 * and every downstream `command.lowercase()` was a latent NPE. kotlinx.serialization
 * validates required fields at parse time, so a malformed frame fails fast at the edge
 * with a decode error instead of surfacing as a crash three layers in.
 */
object Protocol {

    /** Bumped when a breaking change is made to the frame shape. */
    const val VERSION: Int = 2

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
    }
}

@Serializable
data class CommandRequest(
    val id: String,
    val command: String,
    val params: JsonObject = JsonObject(emptyMap()),
    /**
     * When the client issued this request, in epoch milliseconds.
     *
     * Optional, so that a client written against the previous protocol keeps working for
     * every ordinary command. Privileged commands refuse to run without it: the replay
     * guard cannot judge staleness on a request that does not say when it was made, and a
     * re-executed shell command is a second real action rather than an idempotent retry.
     */
    val timestamp: Long? = null,
)

@Serializable
data class CommandResponse(
    val id: String,
    val success: Boolean,
    val data: JsonElement? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: ErrorCode? = null,
) {
    companion object {
        fun success(id: String, data: JsonElement): CommandResponse =
            CommandResponse(id = id, success = true, data = data)

        fun error(id: String, code: ErrorCode, message: String): CommandResponse =
            CommandResponse(id = id, success = false, error = message, errorCode = code)
    }
}

/**
 * Sent by the device immediately after a client completes the authenticated handshake,
 * so the MCP server can negotiate behaviour instead of guessing from version strings.
 */
@Serializable
data class ServerHello(
    val type: String = "hello",
    val protocolVersion: Int = Protocol.VERSION,
    val appVersion: String,
    val encrypted: Boolean,
    val capabilities: List<String>,
)
