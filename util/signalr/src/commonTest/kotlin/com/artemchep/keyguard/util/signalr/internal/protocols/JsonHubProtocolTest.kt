package com.artemchep.keyguard.util.signalr.internal.protocols

import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.logger.Logger
import com.artemchep.keyguard.util.signalr.TransferFormat
import com.artemchep.keyguard.util.signalr.internal.RECORD_SEPARATOR
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonHubProtocolTest {
    private val protocol = JsonHubProtocol(Logger.Empty)

    @Test
    fun `metadata matches SignalR json protocol v1`() {
        assertEquals("json", protocol.name)
        assertEquals(1, protocol.version)
        assertEquals(TransferFormat.Text, protocol.transferFormat)
    }

    @Test
    fun `parse accepts reference json hub message shapes`() {
        assertIs<HubMessage.Invocation.NonBlocking>(
            parseSingle("""{"type":1,"headers":{"Foo":"Bar"},"target":"Target","arguments":[1,"Foo"]}"""),
        )

        val streamedInvocation = assertIs<HubMessage.Invocation.NonBlocking>(
            parseSingle("""{"type":1,"target":"Target","arguments":[],"streamIds":["__test_id__"]}"""),
        )
        assertEquals(listOf("__test_id__"), streamedInvocation.streamIds)

        val blockingInvocation = assertIs<HubMessage.Invocation.Blocking>(
            parseSingle("""{"type":1,"invocationId":"123","target":"Target","arguments":[1],"streamIds":["stream-1"]}"""),
        )
        assertEquals("123", blockingInvocation.invocationId)
        assertEquals(listOf("stream-1"), blockingInvocation.streamIds)

        val streamItem = assertIs<HubMessage.StreamItem>(
            parseSingle("""{"item":null,"invocationId":"123","type":2}"""),
        )
        assertEquals(JsonNull, streamItem.item)

        val nullCompletion = assertIs<HubMessage.Completion.Resulted>(
            parseSingle("""{"type":3,"invocationId":"123","result":null}"""),
        )
        assertEquals(JsonNull, nullCompletion.result)

        assertIs<HubMessage.Completion.Simple>(
            parseSingle("""{"type":3,"invocationId":"123"}"""),
        )
        assertIs<HubMessage.Completion.Error>(
            parseSingle("""{"type":3,"invocationId":"123","error":"Whoops!"}"""),
        )
        assertIs<HubMessage.StreamInvocation>(
            parseSingle("""{"type":4,"invocationId":"123","target":"Target","arguments":[true]}"""),
        )
        assertIs<HubMessage.CancelInvocation>(
            parseSingle("""{"type":5,"invocationId":"123"}"""),
        )
        assertIs<HubMessage.Ping>(
            parseSingle("""{"type":6}"""),
        )

        val close = assertIs<HubMessage.Close>(
            parseSingle("""{"type":7,"error":"Error!","allowReconnect":true}"""),
        )
        assertEquals("Error!", close.error)
        assertTrue(close.allowReconnect)
    }

    @Test
    fun `write emits reference-compatible json for v1 hub message shapes`() {
        val cases = listOf(
            HubMessage.Invocation.NonBlocking(
                target = "Target",
                arguments = listOf(JsonPrimitive(1), JsonPrimitive("Foo")),
            ) to """{"type":1,"target":"Target","arguments":[1,"Foo"]}""",
            HubMessage.Invocation.NonBlocking(
                target = "Target",
                arguments = emptyList(),
                streamIds = listOf("__test_id__"),
            ) to """{"type":1,"target":"Target","arguments":[],"streamIds":["__test_id__"]}""",
            HubMessage.Invocation.Blocking(
                invocationId = "123",
                target = "Target",
                arguments = listOf(JsonPrimitive(1)),
            ) to """{"type":1,"invocationId":"123","target":"Target","arguments":[1]}""",
            HubMessage.StreamItem(
                invocationId = "123",
                item = JsonPrimitive("Foo"),
            ) to """{"type":2,"invocationId":"123","item":"Foo"}""",
            HubMessage.StreamItem(
                invocationId = "123",
                item = JsonNull,
            ) to """{"type":2,"invocationId":"123","item":null}""",
            HubMessage.Completion.Resulted(
                invocationId = "123",
                result = JsonNull,
            ) to """{"type":3,"invocationId":"123","result":null}""",
            HubMessage.Completion.Simple(
                invocationId = "123",
            ) to """{"type":3,"invocationId":"123"}""",
            HubMessage.Completion.Error(
                invocationId = "123",
                error = "Whoops!",
            ) to """{"type":3,"invocationId":"123","error":"Whoops!"}""",
            HubMessage.StreamInvocation(
                invocationId = "123",
                target = "Target",
                arguments = listOf(JsonPrimitive(true)),
            ) to """{"type":4,"invocationId":"123","target":"Target","arguments":[true]}""",
            HubMessage.CancelInvocation(
                invocationId = "123",
            ) to """{"type":5,"invocationId":"123"}""",
            HubMessage.Ping() to """{"type":6}""",
            HubMessage.Close() to """{"type":7}""",
            HubMessage.Close(
                error = "Error!",
                allowReconnect = true,
            ) to """{"type":7,"error":"Error!","allowReconnect":true}""",
        )

        cases.forEach { (message, expectedJson) ->
            assertJsonEquals(
                expectedJson,
                protocol.writeMessage(message)
                    .decodeToString()
                    .also { encoded ->
                        assertTrue(encoded.endsWith(RECORD_SEPARATOR.toString()))
                    }
                    .removeSuffix(RECORD_SEPARATOR.toString()),
            )
        }
    }

    @Test
    fun `parse ignores unknown future message types`() {
        val messages = protocol.parseMessages(
            frame("""{"type":99,"future":true}"""),
        )

        assertEquals(emptyList(), messages)
    }

    @Test
    fun `parse rejects completion with non string error`() {
        val messages = protocol.parseMessages(
            frame("""{"type":3,"invocationId":"123","error":false}"""),
        )

        assertEquals(emptyList(), messages)
    }

    @Test
    fun `parse rejects completion with both error and result`() {
        val messages = protocol.parseMessages(
            frame("""{"type":3,"invocationId":"123","error":"Whoops!","result":42}"""),
        )

        assertEquals(emptyList(), messages)
    }

    @Test
    fun `parse fails on incomplete text frames`() {
        assertFailsWith<RuntimeException> {
            protocol.parseMessages(
                """{"type":6}""".encodeToByteArray(),
            )
        }
    }

    private fun parseSingle(
        value: String,
    ): HubMessage = protocol.parseMessages(frame(value)).single()

    private fun frame(
        value: String,
    ): ByteArray = "$value$RECORD_SEPARATOR".encodeToByteArray()

    private fun assertJsonEquals(
        expected: String,
        actual: String,
    ) {
        assertEquals(
            Json.decodeFromString(JsonElement.serializer(), expected),
            Json.decodeFromString(JsonElement.serializer(), actual),
        )
    }
}
