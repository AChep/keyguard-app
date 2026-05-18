package com.artemchep.keyguard.util.signalr

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
@Serializable(with = HubMessage.MessageSerializer::class)
sealed class HubMessage {
    @EncodeDefault
    abstract val type: Int

    @Serializable
    sealed class Invocation : HubMessage() {
        abstract val target: String
        abstract val arguments: List<JsonElement>
        abstract val streamIds: List<String>?

        @Serializable
        data class Blocking(
            override val target: String,
            override val arguments: List<JsonElement>,
            val invocationId: String,
            override val streamIds: List<String>? = null,
        ) : Invocation() {
            @EncodeDefault
            override val type: Int = HubMessageType.INVOCATION.value
        }

        @Serializable
        data class NonBlocking(
            override val target: String,
            override val arguments: List<JsonElement>,
            override val streamIds: List<String>? = null,
        ) : Invocation() {
            @EncodeDefault
            override val type: Int = HubMessageType.INVOCATION.value
        }
    }

    @Serializable
    data class StreamInvocation(
        val target: String,
        val arguments: List<JsonElement>,
        val invocationId: String,
        val streamIds: List<String>? = null,
    ) : HubMessage() {
        @EncodeDefault
        override val type: Int = HubMessageType.STREAM_INVOCATION.value
    }

    @Serializable
    sealed class Completion : HubMessage() {
        abstract val invocationId: String

        @EncodeDefault
        override val type: Int = HubMessageType.COMPLETION.value

        @Serializable
        data class Simple(
            override val invocationId: String,
        ) : Completion()

        @Serializable
        data class Resulted(
            override val invocationId: String,
            val result: JsonElement,
        ) : Completion()

        @Serializable
        data class Error(
            override val invocationId: String,
            val error: String,
        ) : Completion()
    }

    @Serializable
    class Ping : HubMessage() {
        @EncodeDefault
        override val type: Int = HubMessageType.PING.value
    }

    @Serializable
    data class Close(
        val allowReconnect: Boolean = false,
        val error: String? = null,
    ) : HubMessage() {
        @EncodeDefault
        override val type: Int = HubMessageType.CLOSE.value
    }

    @Serializable
    data class StreamItem(
        val invocationId: String,
        val item: JsonElement,
    ) : HubMessage() {
        @EncodeDefault
        override val type: Int = HubMessageType.STREAM_ITEM.value
    }

    @Serializable
    data class CancelInvocation(
        val invocationId: String,
    ) : HubMessage() {
        @EncodeDefault
        override val type: Int = HubMessageType.CANCEL_INVOCATION.value
    }

    object MessageSerializer : JsonContentPolymorphicSerializer<HubMessage>(HubMessage::class) {
        override fun selectDeserializer(
            element: JsonElement,
        ): DeserializationStrategy<HubMessage> {
            val jsonObject = element.jsonObject
            return when (val type = jsonObject["type"]?.jsonPrimitive?.int) {
                HubMessageType.INVOCATION.value -> when {
                    jsonObject["invocationId"]?.jsonPrimitive?.contentOrNull != null -> Invocation.Blocking.serializer()
                    else -> Invocation.NonBlocking.serializer()
                }

                HubMessageType.STREAM_INVOCATION.value -> StreamInvocation.serializer()
                HubMessageType.PING.value -> Ping.serializer()
                HubMessageType.CLOSE.value -> Close.serializer()
                HubMessageType.STREAM_ITEM.value -> StreamItem.serializer()
                HubMessageType.CANCEL_INVOCATION.value -> CancelInvocation.serializer()
                HubMessageType.COMPLETION.value -> when {
                    jsonObject["error"]?.jsonPrimitive?.isString != null -> Completion.Error.serializer()
                    jsonObject.containsKey("result") -> Completion.Resulted.serializer()
                    else -> Completion.Simple.serializer()
                }

                else -> error("Unknown type ($type) when deserializing SignalR structure: $element")
            }
        }
    }
}
