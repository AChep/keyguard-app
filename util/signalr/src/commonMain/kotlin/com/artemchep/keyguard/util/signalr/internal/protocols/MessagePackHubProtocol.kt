package com.artemchep.keyguard.util.signalr.internal.protocols

import com.artemchep.keyguard.util.messagepack.MessagePackCodec
import com.artemchep.keyguard.util.messagepack.MessagePackFrameCodec
import com.artemchep.keyguard.util.messagepack.toJsonElement
import com.artemchep.keyguard.util.messagepack.toMessagePackDynamic
import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.HubProtocol
import com.artemchep.keyguard.util.signalr.TransferFormat
import kotlinx.serialization.json.JsonElement

class MessagePackHubProtocol : HubProtocol {
    private companion object {

        const val PROTOCOL_NAME = "messagepack"
        const val PROTOCOL_VERSION = 1

        const val INVOCATION = 1
        const val STREAM_ITEM = 2
        const val COMPLETION = 3
        const val STREAM_INVOCATION = 4
        const val CANCEL_INVOCATION = 5
        const val PING = 6
        const val CLOSE = 7

        const val ERROR_RESULT = 1
        const val VOID_RESULT = 2
        const val NON_VOID_RESULT = 3
    }

    override val name: String get() = PROTOCOL_NAME
    override val version: Int get() = PROTOCOL_VERSION
    override val transferFormat: TransferFormat get() = TransferFormat.Binary

    override fun parseMessages(
        payload: ByteArray,
    ): List<HubMessage> = MessagePackFrameCodec
        .readFrames(payload)
        .mapNotNull { frame ->
            readHubMessage(
                MessagePackCodec.decodeDynamic(frame),
            )
        }

    override fun writeMessage(
        message: HubMessage,
    ): ByteArray {
        val content = MessagePackCodec.encodeDynamic(
            writeHubMessage(message),
        )
        return MessagePackFrameCodec.frame(content)
    }

    private fun readHubMessage(
        value: Any?,
    ): HubMessage? {
        val items = value as? List<*>
            ?: return null
        if (items.isEmpty()) {
            return null
        }

        return when (items[0].toIntOrNull()) {
            INVOCATION ->
                readInvocation(items)

            STREAM_ITEM ->
                readStreamItem(items)

            COMPLETION ->
                readCompletion(items)

            STREAM_INVOCATION ->
                readStreamInvocation(items)

            CANCEL_INVOCATION ->
                readCancelInvocation(items)

            PING ->
                HubMessage.Ping()

            CLOSE ->
                readClose(items)

            else ->
                null
        }
    }

    private fun readInvocation(
        items: List<*>,
    ): HubMessage.Invocation {
        val invocationId = items
            .getOrNull(2)
            .toStringOrNull()
            ?.takeIf { it.isNotEmpty() }
        val target = items
            .getString(index = 3, field = "target")
        val arguments = items
            .getList(index = 4, field = "arguments")
            .map(Any?::toJsonElement)
        val streamIds = items
            .getOrNull(5)
            .toStringListOrNull()

        return if (invocationId != null) {
            HubMessage.Invocation.Blocking(
                target = target,
                arguments = arguments,
                invocationId = invocationId,
                streamIds = streamIds,
            )
        } else {
            HubMessage.Invocation.NonBlocking(
                target = target,
                arguments = arguments,
                streamIds = streamIds,
            )
        }
    }

    private fun readStreamInvocation(
        items: List<*>,
    ): HubMessage.StreamInvocation = HubMessage.StreamInvocation(
        invocationId = items.getString(index = 2, field = "invocationId"),
        target = items.getString(index = 3, field = "target"),
        arguments = items
            .getList(index = 4, field = "arguments")
            .map(Any?::toJsonElement),
        streamIds = items
            .getOrNull(5)
            .toStringListOrNull(),
    )

    private fun readStreamItem(
        items: List<*>,
    ): HubMessage.StreamItem = HubMessage.StreamItem(
        invocationId = items.getString(index = 2, field = "invocationId"),
        item = items
            .getOrNull(3)
            .toJsonElement(),
    )

    private fun readCompletion(
        items: List<*>,
    ): HubMessage.Completion {
        val invocationId = items.getString(index = 2, field = "invocationId")
        return when (items.getOrNull(3).toIntOrNull()) {
            ERROR_RESULT -> HubMessage.Completion.Error(
                invocationId = invocationId,
                error = items.getString(index = 4, field = "error"),
            )

            VOID_RESULT -> HubMessage.Completion.Simple(
                invocationId = invocationId,
            )

            NON_VOID_RESULT -> HubMessage.Completion.Resulted(
                invocationId = invocationId,
                result = items
                    .getOrNull(4)
                    .toJsonElement(),
            )

            else -> throw IllegalArgumentException("Unknown MessagePack completion result.")
        }
    }

    private fun readCancelInvocation(
        items: List<*>,
    ): HubMessage.CancelInvocation = HubMessage.CancelInvocation(
        invocationId = items.getString(index = 2, field = "invocationId"),
    )

    private fun readClose(
        items: List<*>,
    ): HubMessage.Close = HubMessage.Close(
        error = items
            .getOrNull(1)
            .toStringOrNull(),
        allowReconnect = items
            .getOrNull(2) as? Boolean
            ?: false,
    )

    private fun writeHubMessage(
        message: HubMessage,
    ): List<Any?> = when (message) {
        is HubMessage.Invocation ->
            writeInvocation(message)

        is HubMessage.StreamItem -> listOf(
            STREAM_ITEM,
            emptyMap<String, Any?>(),
            message.invocationId,
            message.item.toMessagePackDynamic(),
        )

        is HubMessage.Completion ->
            writeCompletion(message)

        is HubMessage.StreamInvocation ->
            listOf(
                STREAM_INVOCATION,
                emptyMap<String, Any?>(),
                message.invocationId,
                message.target,
                message.arguments.toMessagePackDynamicList(),
                message.streamIds.orEmpty(),
            )

        is HubMessage.CancelInvocation -> listOf(
            CANCEL_INVOCATION,
            emptyMap<String, Any?>(),
            message.invocationId,
        )

        is HubMessage.Ping -> listOf(PING)

        is HubMessage.Close -> listOf(
            CLOSE,
            message.error,
            message.allowReconnect,
        )
    }

    private fun writeInvocation(
        message: HubMessage.Invocation,
    ): List<Any?> {
        val items = mutableListOf<Any?>(
            INVOCATION,
            emptyMap<String, Any?>(),
        )
        when (message) {
            is HubMessage.Invocation.Blocking ->
                items.add(message.invocationId)

            is HubMessage.Invocation.NonBlocking ->
                items.add(null)
        }
        items.add(message.target)
        items.add(message.arguments.toMessagePackDynamicList())
        items.add(message.streamIds.orEmpty())
        return items
    }

    private fun writeCompletion(
        message: HubMessage.Completion,
    ): List<Any?> = when (message) {
        is HubMessage.Completion.Error -> listOf(
            COMPLETION,
            emptyMap<String, Any?>(),
            message.invocationId,
            ERROR_RESULT,
            message.error,
        )

        is HubMessage.Completion.Simple -> listOf(
            COMPLETION,
            emptyMap<String, Any?>(),
            message.invocationId,
            VOID_RESULT,
        )

        is HubMessage.Completion.Resulted -> listOf(
            COMPLETION,
            emptyMap<String, Any?>(),
            message.invocationId,
            NON_VOID_RESULT,
            message.result.toMessagePackDynamic(),
        )
    }

    private fun List<JsonElement>.toMessagePackDynamicList(): List<Any?> = map {
        it.toMessagePackDynamic()
    }

    private fun List<*>.getString(
        index: Int,
        field: String,
    ): String = getOrNull(index).toStringOrNull()
        ?: throw IllegalArgumentException("Missing SignalR MessagePack '$field'.")

    private fun List<*>.getList(
        index: Int,
        field: String,
    ): List<*> = getOrNull(index) as? List<*>
        ?: throw IllegalArgumentException("Missing SignalR MessagePack '$field'.")

    private fun Any?.toIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull(radix = 10)
        else -> null
    }

    private fun Any?.toStringOrNull(): String? = this as? String

    private fun Any?.toStringListOrNull(): List<String>? = (this as? List<*>)
        ?.mapNotNull { it as? String }
        ?.takeIf { it.isNotEmpty() }
}
