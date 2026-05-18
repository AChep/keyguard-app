package com.artemchep.keyguard.util.signalr

import com.artemchep.keyguard.util.messagepack.MessagePackCodec
import com.artemchep.keyguard.util.messagepack.MessagePackFrameCodec
import com.artemchep.keyguard.util.signalr.internal.protocols.MessagePackHubProtocol
import com.ensarsarajcic.kotlinx.serialization.msgpack.extensions.MsgPackExtension
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class MessagePackHubProtocolTest {
    private val protocol = MessagePackHubProtocol()

    @Test
    fun `metadata matches SignalR MessagePack protocol v1`() {
        assertEquals("messagepack", protocol.name)
        assertEquals(1, protocol.version)
        assertEquals(TransferFormat.Binary, protocol.transferFormat)
    }

    @Test
    fun `write matches ASP NET Core MessagePack baselines for v1 hub messages`() {
        val cases = listOf(
            WireCase(
                name = "InvocationWithNoHeadersAndNoArgs",
                message = HubMessage.Invocation.Blocking(
                    invocationId = "xyz",
                    target = "method",
                    arguments = emptyList(),
                ),
                unframedBase64 = "lgGAo3h5eqZtZXRob2SQkA==",
            ),
            WireCase(
                name = "InvocationWithNoHeadersNoIdAndNoArgs",
                message = HubMessage.Invocation.NonBlocking(
                    target = "method",
                    arguments = emptyList(),
                ),
                unframedBase64 = "lgGAwKZtZXRob2SQkA==",
            ),
            WireCase(
                name = "InvocationWithNoHeadersNoIdIntAndStringArgs",
                message = HubMessage.Invocation.NonBlocking(
                    target = "method",
                    arguments = listOf(JsonPrimitive(42), JsonPrimitive("string")),
                ),
                unframedBase64 = "lgGAwKZtZXRob2SSKqZzdHJpbmeQ",
            ),
            WireCase(
                name = "InvocationWithStreamArgument",
                message = HubMessage.Invocation.NonBlocking(
                    target = "Target",
                    arguments = emptyList(),
                    streamIds = listOf("__test_id__"),
                ),
                unframedBase64 = "lgGAwKZUYXJnZXSQkatfX3Rlc3RfaWRfXw==",
            ),
            WireCase(
                name = "StreamItemWithNoHeadersAndIntItem",
                message = HubMessage.StreamItem(
                    invocationId = "xyz",
                    item = JsonPrimitive(42),
                ),
                unframedBase64 = "lAKAo3h5eio=",
            ),
            WireCase(
                name = "StreamItemWithNoHeadersAndBoolItem",
                message = HubMessage.StreamItem(
                    invocationId = "xyz",
                    item = JsonPrimitive(true),
                ),
                unframedBase64 = "lAKAo3h5esM=",
            ),
            WireCase(
                name = "CompletionWithNoHeadersAndError",
                message = HubMessage.Completion.Error(
                    invocationId = "xyz",
                    error = "Error not found!",
                ),
                unframedBase64 = "lQOAo3h5egGwRXJyb3Igbm90IGZvdW5kIQ==",
            ),
            WireCase(
                name = "CompletionWithNoHeadersAndNoResult",
                message = HubMessage.Completion.Simple(
                    invocationId = "xyz",
                ),
                unframedBase64 = "lAOAo3h5egI=",
            ),
            WireCase(
                name = "CompletionWithNoHeadersAndIntResult",
                message = HubMessage.Completion.Resulted(
                    invocationId = "xyz",
                    result = JsonPrimitive(42),
                ),
                unframedBase64 = "lQOAo3h5egMq",
            ),
            WireCase(
                name = "CompletionWithNoHeadersAndNullResult",
                message = HubMessage.Completion.Resulted(
                    invocationId = "xyz",
                    result = JsonNull,
                ),
                unframedBase64 = "lQOAo3h5egPA",
            ),
            WireCase(
                name = "StreamInvocationWithNoHeadersAndIntAndStringArgs",
                message = HubMessage.StreamInvocation(
                    invocationId = "xyz",
                    target = "method",
                    arguments = listOf(JsonPrimitive(42), JsonPrimitive("string")),
                ),
                unframedBase64 = "lgSAo3h5eqZtZXRob2SSKqZzdHJpbmeQ",
            ),
            WireCase(
                name = "CancelInvocationWithNoHeaders",
                message = HubMessage.CancelInvocation(
                    invocationId = "xyz",
                ),
                unframedBase64 = "kwWAo3h5eg==",
            ),
            WireCase(
                name = "Ping",
                message = HubMessage.Ping(),
                unframedBase64 = "kQY=",
            ),
            WireCase(
                name = "CloseMessage",
                message = HubMessage.Close(),
                unframedBase64 = "kwfAwg==",
            ),
            WireCase(
                name = "CloseMessage_HasErrorAndAllowReconnect",
                message = HubMessage.Close(
                    error = "Error!",
                    allowReconnect = true,
                ),
                unframedBase64 = "kwemRXJyb3Ihww==",
            ),
        )

        cases.forEach { case ->
            assertContentEquals(
                frameBase64(case.unframedBase64),
                protocol.writeMessage(case.message),
                case.name,
            )
        }
    }

    @Test
    fun `parse accepts ASP NET Core MessagePack baselines for stream ids and null results`() {
        val invocation = assertIs<HubMessage.Invocation.NonBlocking>(
            protocol.parseMessages(
                frameBase64("lgGAwKZUYXJnZXSQkatfX3Rlc3RfaWRfXw=="),
            ).single(),
        )
        assertEquals("Target", invocation.target)
        assertEquals(listOf("__test_id__"), invocation.streamIds)

        val completion = assertIs<HubMessage.Completion.Resulted>(
            protocol.parseMessages(
                frameBase64("lQOAo3h5egPA"),
            ).single(),
        )
        assertEquals("xyz", completion.invocationId)
        assertEquals(JsonNull, completion.result)
    }

    @Test
    fun `parse accepts every Bitwarden registered ReceiveMessage notification type`() {
        bitwardenRegisteredTypes.forEach { type ->
            val invocation = parseSingleInvocation(
                invocationFrame(
                    target = "ReceiveMessage",
                    arguments = listOf(
                        notificationArgument(
                            type = type,
                            contextId = "device-$type",
                            payload = payloadFor(type),
                        ),
                    ),
                ),
            )

            assertEquals("ReceiveMessage", invocation.target)
            assertEquals(
                JsonObject(
                    mapOf(
                        "ContextId" to JsonPrimitive("device-$type"),
                        "Type" to JsonPrimitive(type),
                        "Payload" to payloadFor(type).toJsonObject(),
                    ),
                ),
                invocation.arguments.single(),
            )
        }
    }

    @Test
    fun `parse accepts vaultwarden ReceiveMessage variants`() {
        val variants = vaultwardenReceiveMessageTypes.map(::vaultwardenNotificationArgument)

        variants.forEach { argument ->
            val invocation = parseSingleInvocation(
                invocationFrame(
                    target = "ReceiveMessage",
                    arguments = listOf(argument),
                ),
            )
            val message = invocation.arguments.single() as JsonObject

            assertEquals("ReceiveMessage", invocation.target)
            assertEquals(argument["Type"], (message["Type"] as JsonPrimitive).content.toInt())
            assertTrue(message.containsKey("Payload"))
        }
    }

    @Test
    fun `parse accepts anonymous auth response target typo used by Bitwarden and vaultwarden`() {
        val invocation = parseSingleInvocation(
            invocationFrame(
                target = "AuthRequestResponseRecieved",
                arguments = listOf(
                    mapOf(
                        "Type" to 16,
                        "UserId" to "user-1",
                        "Payload" to mapOf(
                            "Id" to "auth-request-1",
                            "UserId" to "user-1",
                        ),
                    ),
                ),
            ),
        )

        assertEquals("AuthRequestResponseRecieved", invocation.target)
        assertEquals(
            JsonObject(
                mapOf(
                    "Type" to JsonPrimitive(16),
                    "UserId" to JsonPrimitive("user-1"),
                    "Payload" to JsonObject(
                        mapOf(
                            "Id" to JsonPrimitive("auth-request-1"),
                            "UserId" to JsonPrimitive("user-1"),
                        ),
                    ),
                ),
            ),
            invocation.arguments.single(),
        )
    }

    @Test
    fun `parse accepts Bitwarden Heartbeat invocation`() {
        val invocation = parseSingleInvocation(
            invocationFrame(
                target = "Heartbeat",
                arguments = emptyList(),
            ),
        )

        assertEquals("Heartbeat", invocation.target)
        assertEquals(emptyList(), invocation.arguments)
    }

    @Test
    fun `parse accepts protocol ping used by vaultwarden keepalive`() {
        val messages = protocol.parseMessages(
            dynamicFrame(
                listOf(6),
            ),
        )

        assertIs<HubMessage.Ping>(messages.single())
    }

    @Test
    fun `parse accepts multiple SignalR frames in one payload`() {
        val payload = invocationFrame(
            target = "Heartbeat",
            arguments = emptyList(),
        ) + dynamicFrame(listOf(6))

        val messages = protocol.parseMessages(payload)

        assertEquals(2, messages.size)
        assertIs<HubMessage.Invocation.NonBlocking>(messages[0])
        assertIs<HubMessage.Ping>(messages[1])
    }

    @Test
    fun `parse accepts all SignalR hub message types`() {
        val cases = listOf(
            dynamicFrame(
                listOf(
                    1,
                    emptyMap<String, Any?>(),
                    null,
                    "ReceiveMessage",
                    listOf(notificationArgument(type = 5)),
                ),
            ) to HubMessage.Invocation.NonBlocking::class,
            dynamicFrame(
                listOf(
                    1,
                    emptyMap<String, Any?>(),
                    "invocation-1",
                    "NeedsResult",
                    listOf("arg"),
                    listOf("stream-1"),
                ),
            ) to HubMessage.Invocation.Blocking::class,
            dynamicFrame(
                listOf(
                    2,
                    emptyMap<String, Any?>(),
                    "invocation-1",
                    mapOf("value" to 7),
                ),
            ) to HubMessage.StreamItem::class,
            dynamicFrame(
                listOf(
                    3,
                    emptyMap<String, Any?>(),
                    "invocation-1",
                    1,
                    "boom",
                ),
            ) to HubMessage.Completion.Error::class,
            dynamicFrame(
                listOf(
                    3,
                    emptyMap<String, Any?>(),
                    "invocation-1",
                    2,
                ),
            ) to HubMessage.Completion.Simple::class,
            dynamicFrame(
                listOf(
                    3,
                    emptyMap<String, Any?>(),
                    "invocation-1",
                    3,
                    mapOf("ok" to true),
                ),
            ) to HubMessage.Completion.Resulted::class,
            dynamicFrame(
                listOf(
                    4,
                    emptyMap<String, Any?>(),
                    "stream-1",
                    "StreamItems",
                    listOf(1, 2),
                    listOf("upload-1"),
                ),
            ) to HubMessage.StreamInvocation::class,
            dynamicFrame(
                listOf(
                    5,
                    emptyMap<String, Any?>(),
                    "stream-1",
                ),
            ) to HubMessage.CancelInvocation::class,
            dynamicFrame(listOf(6)) to HubMessage.Ping::class,
            dynamicFrame(listOf(7, "closing", true)) to HubMessage.Close::class,
        )

        cases.forEach { (frame, expectedType) ->
            val message = protocol.parseMessages(frame).single()
            assertTrue(
                expectedType.isInstance(message),
                "Expected $expectedType, got $message.",
            )
        }
    }

    @Test
    fun `write round trips all outgoing SignalR hub message types`() {
        val messages = listOf(
            HubMessage.Invocation.NonBlocking(
                target = "ReceiveMessage",
                arguments = listOf(notificationArgument(type = 5).toJsonElement()),
            ),
            HubMessage.Invocation.Blocking(
                target = "NeedsResult",
                arguments = listOf(JsonPrimitive("arg")),
                invocationId = "invocation-1",
                streamIds = listOf("stream-1"),
            ),
            HubMessage.StreamItem(
                invocationId = "invocation-1",
                item = JsonObject(mapOf("value" to JsonPrimitive(7))),
            ),
            HubMessage.Completion.Error(
                invocationId = "invocation-1",
                error = "boom",
            ),
            HubMessage.Completion.Simple(
                invocationId = "invocation-1",
            ),
            HubMessage.Completion.Resulted(
                invocationId = "invocation-1",
                result = JsonObject(mapOf("ok" to JsonPrimitive(true))),
            ),
            HubMessage.StreamInvocation(
                target = "StreamItems",
                arguments = listOf(JsonPrimitive(1)),
                invocationId = "stream-1",
                streamIds = listOf("upload-1"),
            ),
            HubMessage.CancelInvocation(
                invocationId = "stream-1",
            ),
            HubMessage.Ping(),
            HubMessage.Close(
                allowReconnect = true,
                error = "closing",
            ),
        )

        messages.forEach { message ->
            val parsed = protocol.parseMessages(
                protocol.writeMessage(message),
            ).single()

            assertEquals(message::class, parsed::class)
        }
    }

    @Test
    fun `parse ignores unknown SignalR hub message type`() {
        val messages = protocol.parseMessages(
            dynamicFrame(
                listOf(
                    99,
                    emptyMap<String, Any?>(),
                ),
            ),
        )

        assertEquals(emptyList(), messages)
    }

    @Test
    fun `parse fails on incomplete SignalR binary frame`() {
        assertFailsWith<IllegalArgumentException> {
            protocol.parseMessages(
                byteArrayOf(
                    0x05,
                    0x01,
                ),
            )
        }
    }

    private fun parseSingleInvocation(
        frame: ByteArray,
    ): HubMessage.Invocation.NonBlocking =
        assertIs<HubMessage.Invocation.NonBlocking>(protocol.parseMessages(frame).single())

    private fun invocationFrame(
        target: String,
        arguments: List<Any?>,
    ): ByteArray = dynamicFrame(
        listOf(
            1,
            emptyMap<String, Any?>(),
            null,
            target,
            arguments,
        ),
    )

    private fun dynamicFrame(
        value: Any?,
    ): ByteArray = MessagePackFrameCodec.frame(
        MessagePackCodec.encodeDynamic(value),
    )

    private fun frameBase64(
        value: String,
    ): ByteArray = MessagePackFrameCodec.frame(
        Base64.decode(value),
    )

    private fun notificationArgument(
        type: Int,
        contextId: String? = "device-1",
        payload: Map<String, Any?> = mapOf(
            "Id" to "entity-1",
            "UserId" to "user-1",
        ),
    ): Map<String, Any?> = mapOf(
        "ContextId" to contextId,
        "Type" to type,
        "Payload" to payload,
    )

    private fun payloadFor(
        type: Int,
    ): Map<String, Any?> = when (type) {
        0, 1, 2, 9 -> mapOf(
            "Id" to "cipher-1",
            "UserId" to "user-1",
            "OrganizationId" to null,
            "CollectionIds" to null,
            "RevisionDate" to "2026-05-13T00:00:00Z",
        )
        3, 7, 8 -> mapOf(
            "Id" to "folder-1",
            "UserId" to "user-1",
            "RevisionDate" to "2026-05-13T00:00:00Z",
        )
        4, 5, 6, 10, 17, 22 -> mapOf(
            "UserId" to "user-1",
            "Date" to "2026-05-13T00:00:00Z",
        )
        11 -> mapOf(
            "UserId" to "user-1",
            "Reason" to null,
        )
        12, 13, 14 -> mapOf(
            "Id" to "send-1",
            "UserId" to "user-1",
            "RevisionDate" to "2026-05-13T00:00:00Z",
        )
        15, 16 -> mapOf(
            "Id" to "auth-request-1",
            "UserId" to "user-1",
        )
        18 -> mapOf(
            "OrganizationId" to "org-1",
            "Enabled" to true,
        )
        19 -> mapOf(
            "OrganizationId" to "org-1",
            "LimitCollectionCreation" to true,
            "LimitCollectionDeletion" to false,
            "LimitItemDeletion" to true,
        )
        20, 21 -> mapOf(
            "Id" to "notification-1",
            "Priority" to 1,
            "Global" to false,
            "ClientType" to 0,
            "UserId" to "user-1",
            "OrganizationId" to null,
            "InstallationId" to null,
            "TaskId" to null,
            "Title" to "Title",
            "Body" to "Body",
            "CreationDate" to "2026-05-13T00:00:00Z",
            "RevisionDate" to "2026-05-13T00:00:00Z",
            "ReadDate" to null,
            "DeletedDate" to null,
        )
        23 -> mapOf(
            "OrganizationId" to "org-1",
        )
        24 -> mapOf(
            "AdminId" to "admin-1",
            "ProviderId" to "provider-1",
        )
        25 -> mapOf(
            "OrganizationId" to "org-1",
            "Policy" to mapOf(
                "Id" to "policy-1",
                "Enabled" to true,
            ),
        )
        26 -> mapOf(
            "UserId" to "admin-1",
            "OrganizationId" to "org-1",
            "TargetUserId" to "user-1",
            "TargetOrganizationUserId" to "org-user-1",
        )
        27 -> mapOf(
            "UserId" to "user-1",
            "Premium" to true,
        )
        else -> emptyMap()
    }

    private fun vaultwardenNotificationArgument(
        type: Int,
    ): Map<String, Any?> = notificationArgument(
        type = type,
        contextId = when (type) {
            0, 1, 2, 3, 7, 8, 11, 15, 16 -> "device-$type"
            else -> null
        },
        payload = vaultwardenPayloadFor(type),
    )

    private fun vaultwardenPayloadFor(
        type: Int,
    ): Map<String, Any?> = when (type) {
        0, 1, 2 -> mapOf(
            "Id" to "cipher-1",
            "UserId" to if (type == 0) null else "user-1",
            "OrganizationId" to "org-1",
            "CollectionIds" to if (type == 0) {
                listOf("collection-1", "collection-2")
            } else {
                null
            },
            "RevisionDate" to timestamp64(seconds = 1_700_000_000L + type),
        )
        3, 7, 8 -> mapOf(
            "Id" to "folder-1",
            "UserId" to "user-1",
            "RevisionDate" to timestamp64(seconds = 1_700_000_010L + type),
        )
        4, 5, 6, 10 -> mapOf(
            "UserId" to "user-1",
            "Date" to timestamp64(seconds = 1_700_000_020L + type),
        )
        11 -> mapOf(
            "UserId" to "user-1",
            "Date" to timestamp64(seconds = 1_700_000_031L, nanoseconds = 123_000_000),
        )
        12, 13, 14 -> mapOf(
            "Id" to "send-1",
            "UserId" to "user-1",
            "RevisionDate" to timestamp64(seconds = 1_700_000_040L + type),
        )
        15, 16 -> mapOf(
            "Id" to "auth-request-1",
            "UserId" to "user-1",
        )
        100 -> emptyMap()
        else -> error("Unsupported vaultwarden notification type $type.")
    }

    private fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(
        entries.associate { (key, value) ->
            key to value.toJsonElement()
        },
    )

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is List<*> -> JsonArray(map { it.toJsonElement() })
        is Map<*, *> -> JsonObject(
            entries.associate { (key, value) ->
                key.toString() to value.toJsonElement()
            },
        )
        is MsgPackExtension -> JsonPrimitive(this.toString())
        else -> JsonPrimitive(toString())
    }

    private fun timestamp64(
        seconds: Long,
        nanoseconds: Int = 0,
    ): MsgPackExtension {
        val value = (nanoseconds.toLong() shl 34) or seconds
        val bytes = ByteArray(8) { index ->
            ((value ushr ((7 - index) * 8)) and 0xff).toByte()
        }
        return MsgPackExtension(
            type = MsgPackExtension.Type.FIXEXT8,
            extTypeId = (-1).toByte(),
            data = bytes,
        )
    }

    private data class WireCase(
        val name: String,
        val message: HubMessage,
        val unframedBase64: String,
    )

    private companion object {
        val bitwardenRegisteredTypes = listOf(
            0,
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            12,
            13,
            14,
            15,
            16,
            17,
            18,
            19,
            20,
            21,
            22,
            23,
            24,
            25,
            26,
            27,
        )

        val vaultwardenReceiveMessageTypes = listOf(
            0,
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            10,
            11,
            12,
            13,
            14,
            15,
            16,
            100,
        )
    }
}
