package com.artemchep.keyguard.feature.browserextension.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The JSON of the messages: both sides ignore unknown fields, and optional
 * fields are omitted when absent.
 */
val BrowserJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** One request of the extension, README section 4, before its fields are read. */
class BrowserRequestEnvelope(
    val id: Long,
    /** The `type` of the request, `null` when it has none or it is not a string. */
    val type: String?,
    val body: JsonObject,
) {
    /** Reads the fields of the request; a missing or mistyped one is `invalid_request`. */
    inline fun <reified T> decode(): T = try {
        BrowserJson.decodeFromJsonElement<T>(body)
    } catch (_: SerializationException) {
        throw BrowserRequestException(BrowserError.INVALID_REQUEST)
    } catch (_: IllegalArgumentException) {
        throw BrowserRequestException(BrowserError.INVALID_REQUEST)
    }
}

/** What the app answers a request with. */
sealed interface BrowserReply {
    /** `{"id": …, "ok": true, …fields}`. */
    class Ok(val fields: JsonObject) : BrowserReply

    /** `{"id": …, "ok": false, "error": …}`. */
    class Error(val error: BrowserError) : BrowserReply
}

/** A successful reply with the fields of [body]. */
inline fun <reified T> browserOk(body: T): BrowserReply.Ok =
    BrowserReply.Ok(BrowserJson.encodeToJsonElement(body).jsonObject)

/** A successful reply with no fields. */
fun browserOk(): BrowserReply.Ok = BrowserReply.Ok(JsonObject(emptyMap()))

/** The events of README section 6. */
enum class BrowserEvent(val wireName: String) {
    VAULT("vault"),
    REVOKED("revoked"),
    DISABLED("disabled"),
}

/** Reads and writes the messages of README section 4. */
object BrowserMessages {
    /**
     * Reads a whole [message] as a request. A message that is not a UTF-8
     * JSON object, or a request without a valid `id`, throws
     * [BrowserProtocolException]: the session ends.
     */
    fun parseRequest(message: ByteArray): BrowserRequestEnvelope {
        val body = parseObject(message)
            ?: throw BrowserProtocolException("A message that is not a UTF-8 JSON object")
        val id = (body["id"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.longOrNull
            ?.takeIf { it in 1..BrowserProtocol.MAX_ID }
            ?: throw BrowserProtocolException("A request without a valid id")
        val type = (body["type"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        return BrowserRequestEnvelope(id = id, type = type, body = body)
    }

    /**
     * The response to the request [id]. A response that would exceed
     * [BrowserFragments.MAX_MESSAGE_SIZE] becomes `too_large`.
     */
    fun response(id: Long, reply: BrowserReply): ByteArray {
        val message = encode(id, reply)
        if (message.size <= BrowserFragments.MAX_MESSAGE_SIZE) return message
        message.fill(0)
        return encode(id, BrowserReply.Error(BrowserError.TOO_LARGE))
    }

    /** The event [event] with its [fields]. */
    fun event(event: BrowserEvent, fields: JsonObject = JsonObject(emptyMap())): ByteArray =
        encode(
            buildJsonObject {
                put("event", event.wireName)
                fields.forEach { (key, value) -> put(key, value) }
            },
        )

    private fun parseObject(message: ByteArray): JsonObject? = try {
        message.decodeToString(throwOnInvalidSequence = true)
            // The parser recurses into arrays on the stack: a deep one would overflow it.
            .takeIf { nestsAtMost(it, BrowserProtocol.MAX_DEPTH) }
            ?.let(BrowserJson::parseToJsonElement) as? JsonObject
    } catch (_: CharacterCodingException) {
        null
    } catch (_: SerializationException) {
        null
    }

    /** Whether the arrays and objects of the JSON [text] nest at most [maxDepth] deep. */
    private fun nestsAtMost(text: String, maxDepth: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                inString -> inString = c != '"'
                c == '"' -> inString = true
                c == '{' || c == '[' -> if (++depth > maxDepth) return false
                c == '}' || c == ']' -> depth -= 1
            }
        }
        return true
    }

    private fun encode(id: Long, reply: BrowserReply): ByteArray = encode(
        buildJsonObject {
            put("id", id)
            when (reply) {
                is BrowserReply.Ok -> {
                    put("ok", true)
                    reply.fields.forEach { (key, value) -> put(key, value) }
                }

                is BrowserReply.Error -> {
                    put("ok", false)
                    put("error", reply.error.code)
                }
            }
        },
    )

    private fun encode(json: JsonObject): ByteArray =
        BrowserJson.encodeToString(JsonObject.serializer(), json).encodeToByteArray()
}
