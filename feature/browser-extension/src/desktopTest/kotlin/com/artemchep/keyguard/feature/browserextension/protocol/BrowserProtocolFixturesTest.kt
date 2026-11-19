package com.artemchep.keyguard.feature.browserextension.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app against `browserExtension/protocol/fixtures/`, which the extension
 * checks too: each request read as the session reads it, and each response,
 * event and error written as the session writes it, must equal its fixture
 * as a JSON value.
 */
class BrowserProtocolFixturesTest {
    @Test
    fun `every fixture is read`() {
        val expected = BrowserRequestType.entries.map { "${it.wireName}.json" } + EVENTS + ERRORS
        val files = File(requireNotNull(javaClass.getResource("/fixtures/$EVENTS")).toURI())
            .parentFile
            .list()
            .orEmpty()
        assertEquals(expected.sorted(), files.sorted())
    }

    @Test
    fun `requests and responses match the fixtures`() {
        for (type in BrowserRequestType.entries) {
            val name = type.wireName
            val messages = fixture("$name.json").jsonObject
            assertEquals(setOf(REQUEST, RESPONSE), messages.keys, name)
            val request = messages.getValue(REQUEST).jsonObject
            val response = messages.getValue(RESPONSE).jsonObject

            val envelope = BrowserMessages.parseRequest(encode(request))
            assertEquals(name, envelope.type)
            val (fields, reply) = exchange(type, envelope, response - "id" - "ok")
            assertEquals(request - "id" - "type", fields, "$name request")
            assertEquals(response, decode(BrowserMessages.response(envelope.id, reply)), "$name response")
        }
    }

    @Test
    fun `events match the fixtures`() {
        val fixtures = fixture(EVENTS).jsonArray
        val names = fixtures.map { it.jsonObject.name("event") }.toSet()
        assertEquals(BrowserEvent.entries.map { it.wireName }.toSet(), names)
        assertEquals(fixtures, JsonArray(fixtures.map { writeEvent(it.jsonObject) }))
    }

    @Test
    fun `error responses match the fixtures`() {
        val fixtures = fixture(ERRORS).jsonArray
        assertEquals(BrowserError.entries.map { it.code }.toSet(), fixtures.map { it.jsonObject.name("error") }.toSet())
        assertEquals(fixtures, JsonArray(fixtures.map { writeError(it.jsonObject) }))
    }

    @Test
    fun `the app accepts frozen v1 requests and preserves the fields their readers expect`() {
        val v1 = BrowserJson.parseToJsonElement(
            requireNotNull(javaClass.getResource("/compatibility/v1.json")).readText(),
        ).jsonObject
        for ((name, messages) in v1.getValue("exchanges").jsonObject) {
            val request = messages.jsonObject.getValue(REQUEST).jsonObject
            val response = messages.jsonObject.getValue(RESPONSE).jsonObject
            val envelope = BrowserMessages.parseRequest(encode(request))
            val type = requireNotNull(BrowserRequestType.of(name))
            assertEquals(name, envelope.type)
            val (fields, reply) = exchange(type, envelope, response - "id" - "ok")
            assertV1Fields(request - "id" - "type", fields)
            assertV1Fields(response, decode(BrowserMessages.response(envelope.id, reply)))
        }
        for (event in v1.getValue("events").jsonArray) assertV1Fields(event, writeEvent(event.jsonObject))
        for (error in v1.getValue("errors").jsonArray) assertV1Fields(error, writeError(error.jsonObject))
    }

    /**
     * The fields of the [request] as the session reads them, written back,
     * and the reply the session writes with the fixture's [response] fields.
     * Decoding ignores unknown fields, so a field the app doesn't know drops
     * out of the comparison and fails it.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per request type, exhaustive by design.
    private fun exchange(
        type: BrowserRequestType,
        request: BrowserRequestEnvelope,
        response: JsonObject,
    ): Pair<JsonObject, BrowserReply> = when (type) {
        BrowserRequestType.HELLO -> read<HelloRequest>(request) to write<HelloResponse>(response)
        // Answered with no fields.
        BrowserRequestType.CANCEL -> read<CancelRequest>(request) to BrowserReply.Ok(NO_FIELDS)
        BrowserRequestType.PAIR_COMMIT -> read<PairCommitRequest>(request) to write<PairCommitResponse>(response)
        BrowserRequestType.PAIR_REVEAL -> read<PairRevealRequest>(request) to write<VaultResponse>(response)
        // Takes no fields.
        BrowserRequestType.UNLOCK -> NO_FIELDS to write<VaultResponse>(response)

        BrowserRequestType.SUGGEST -> read<SuggestRequest>(request) to write<SuggestResponse>(response)
        BrowserRequestType.SEARCH -> read<SearchRequest>(request) to write<SearchResponse>(response)
        BrowserRequestType.FILL -> read<HandleRequest>(request) to write<FillResponse>(response)
        BrowserRequestType.TOTP -> read<HandleRequest>(request) to write<TotpResponse>(response)
        BrowserRequestType.GENERATE -> read<GenerateRequest>(request) to write<GenerateResponse>(response)
        // Answered with no fields.
        BrowserRequestType.SAVE -> read<SaveRequest>(request) to BrowserReply.Ok(NO_FIELDS)
        BrowserRequestType.NEVER_SAVE -> read<NeverSaveRequest>(request) to BrowserReply.Ok(NO_FIELDS)
        BrowserRequestType.PASSKEY_GET -> read<PasskeyGetRequest>(request) to write<PasskeyResponse>(response)
        BrowserRequestType.PASSKEY_CREATE -> read<PasskeyCreateRequest>(request) to write<PasskeyResponse>(response)
    }

    private inline fun <reified T> read(request: BrowserRequestEnvelope): JsonObject =
        BrowserJson.encodeToJsonElement(request.decode<T>()).jsonObject

    private inline fun <reified T> write(response: JsonObject): BrowserReply =
        browserOk(BrowserJson.decodeFromJsonElement<T>(response))

    private fun fixture(file: String): JsonElement {
        val text = requireNotNull(javaClass.getResource("/fixtures/$file")) { "Missing fixture $file" }.readText()
        return BrowserJson.parseToJsonElement(text)
    }

    private fun encode(message: JsonObject): ByteArray =
        BrowserJson.encodeToString(JsonObject.serializer(), message).encodeToByteArray()

    private companion object {
        const val EVENTS = "events.json"
        const val ERRORS = "errors.json"
        const val REQUEST = "request"
        const val RESPONSE = "response"

        val NO_FIELDS = JsonObject(emptyMap())
    }
}

/** A v1 reader ignores additional object fields, including in nested objects. */
private fun assertV1Fields(expected: JsonElement, actual: JsonElement) {
    when (expected) {
        is JsonObject -> expected.forEach { (key, value) -> assertV1Fields(value, actual.jsonObject.getValue(key)) }
        is JsonArray -> {
            assertEquals(expected.size, actual.jsonArray.size)
            expected.forEachIndexed { index, value -> assertV1Fields(value, actual.jsonArray[index]) }
        }
        else -> assertEquals(expected, actual)
    }
}

private fun writeEvent(fixture: JsonObject): JsonObject {
    val event = BrowserEvent.entries.single { it.wireName == fixture.name("event") }
    val message = when (event) {
        BrowserEvent.VAULT -> {
            val fields = BrowserJson.decodeFromJsonElement<VaultEvent>(fixture - "event")
            BrowserMessages.event(event, browserOk(fields).fields)
        }
        BrowserEvent.REVOKED, BrowserEvent.DISABLED -> BrowserMessages.event(event)
    }
    return decode(message)
}

private fun writeError(fixture: JsonObject): JsonObject {
    val error = BrowserError.entries.single { it.code == fixture.name("error") }
    return decode(BrowserMessages.response(fixture.name("id").toLong(), BrowserReply.Error(error)))
}

private fun JsonObject.name(key: String): String = getValue(key).jsonPrimitive.content

private fun decode(message: ByteArray): JsonObject = BrowserJson.parseToJsonElement(message.decodeToString()).jsonObject

private operator fun JsonObject.minus(key: String) = JsonObject(this.filterKeys { it != key })
