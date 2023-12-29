// Licensed to the .NET Foundation under one or more agreements.
// The .NET Foundation licenses this file to you under the MIT license.
package com.artemchep.keyguard.messagepack

import com.artemchep.keyguard.messagepack.Utils.getLengthHeader
import com.artemchep.keyguard.messagepack.Utils.readLengthHeader
import com.artemchep.keyguard.messagepack.Utils.toPrimitive
import com.artemchep.keyguard.messagepack.Utils.typeToClass
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.signalr.CancelInvocationMessage
import com.microsoft.signalr.CloseMessage
import com.microsoft.signalr.CompletionMessage
import com.microsoft.signalr.HubMessage
import com.microsoft.signalr.HubMessageType
import com.microsoft.signalr.HubProtocol
import com.microsoft.signalr.InvocationBinder
import com.microsoft.signalr.InvocationBindingFailureMessage
import com.microsoft.signalr.InvocationMessage
import com.microsoft.signalr.PingMessage
import com.microsoft.signalr.StreamBindingFailureMessage
import com.microsoft.signalr.StreamInvocationMessage
import com.microsoft.signalr.StreamItem
import org.msgpack.core.MessageFormat
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePackException
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import org.msgpack.jackson.dataformat.MessagePackFactory
import org.msgpack.value.ValueType
import java.io.IOException
import java.lang.reflect.Type
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.*

class MessagePackHubProtocol : HubProtocol {
    private val objectMapper = ObjectMapper(MessagePackFactory())
    private val typeFactory = objectMapper.typeFactory
    override fun getName(): String {
        return "messagepack"
    }

    override fun getVersion(): Int {
        return 1
    }

    override fun parseMessages(
        payload: ByteBuffer,
        binder: InvocationBinder,
    ): List<HubMessage>? {
        var payload = payload
        if (payload.remaining() == 0) {
            return null
        }

        // MessagePack library can't handle read-only ByteBuffer - copy into an array-backed ByteBuffer if this is the case
        if (payload.isReadOnly) {
            val payloadBytes = ByteArray(payload.remaining())
            payload[payloadBytes, 0, payloadBytes.size]
            payload = ByteBuffer.wrap(payloadBytes)
        }
        val hubMessages: MutableList<HubMessage> = ArrayList()
        while (payload.hasRemaining()) {
            var length: Int
            try {
                length = readLengthHeader(payload)
                // Throw if remaining buffer is shorter than length header
                if (payload.remaining() < length) {
                    throw RuntimeException(
                        String.format(
                            "MessagePack message was length %d but claimed to be length %d.",
                            payload.remaining(),
                            length,
                        ),
                    )
                }
            } catch (ex: IOException) {
                throw RuntimeException("Error reading length header.", ex)
            }
            // Instantiate MessageUnpacker
            try {
                MessagePack.newDefaultUnpacker(payload).use { unpacker ->
                    val itemCount = unpacker.unpackArrayHeader()
                    val messageType = HubMessageType.values()[unpacker.unpackInt() - 1]
                    when (messageType) {
                        HubMessageType.INVOCATION -> hubMessages.add(
                            createInvocationMessage(
                                unpacker,
                                binder,
                                itemCount,
                                payload,
                            ),
                        )

                        HubMessageType.STREAM_ITEM -> hubMessages.add(
                            createStreamItemMessage(
                                unpacker,
                                binder,
                                payload,
                            ),
                        )

                        HubMessageType.COMPLETION -> hubMessages.add(
                            createCompletionMessage(
                                unpacker,
                                binder,
                                payload,
                            ),
                        )

                        HubMessageType.STREAM_INVOCATION -> hubMessages.add(
                            createStreamInvocationMessage(unpacker, binder, itemCount, payload),
                        )

                        HubMessageType.CANCEL_INVOCATION -> hubMessages.add(
                            createCancelInvocationMessage(unpacker),
                        )

                        HubMessageType.PING -> hubMessages.add(PingMessage.getInstance())
                        HubMessageType.CLOSE -> hubMessages.add(
                            createCloseMessage(
                                unpacker,
                                itemCount,
                            ),
                        )

                        else -> {}
                    }
                    // Make sure that we actually read the right number of bytes
                    val readBytes = unpacker.totalReadBytes.toInt()
                    if (readBytes != length) {
                        // Check what the last message was
                        // If it was an invocation binding failure, we have to correct the position of the buffer
                        if (hubMessages[hubMessages.size - 1].messageType == HubMessageType.INVOCATION_BINDING_FAILURE) {
                            // Cast to a Buffer to avoid the Java 9+ behavior where ByteBuffer.position(int) overrides Buffer.position(int),
                            // Returning a ByteBuffer rather than a Buffer. This causes issues on Android - see https://github.com/dotnet/aspnetcore/pull/26614
                            (payload as Buffer).position(payload.position() + (length - readBytes))
                        } else {
                            throw RuntimeException(
                                String.format(
                                    "MessagePack message was length %d but claimed to be length %d.",
                                    readBytes,
                                    length,
                                ),
                            )
                        }
                    }
                    unpacker.close()
                    // Cast to a Buffer to avoid the Java 9+ behavior where ByteBuffer.position(int) overrides Buffer.position(int),
                    // Returning a ByteBuffer rather than a Buffer. This causes issues on Android - see https://github.com/dotnet/aspnetcore/pull/26614
                    (payload as Buffer).position(payload.position() + readBytes)
                }
            } catch (ex: MessagePackException) {
                throw RuntimeException("Error reading MessagePack data.", ex)
            } catch (ex: IOException) {
                throw RuntimeException("Error reading MessagePack data.", ex)
            }
        }
        return hubMessages
    }

    override fun writeMessage(hubMessage: HubMessage): ByteBuffer {
        val messageType = hubMessage.messageType
        return try {
            val message: ByteArray
            message = when (messageType) {
                HubMessageType.INVOCATION -> writeInvocationMessage(hubMessage as InvocationMessage)
                HubMessageType.STREAM_ITEM -> writeStreamItemMessage(hubMessage as StreamItem)
                HubMessageType.COMPLETION -> writeCompletionMessage(hubMessage as CompletionMessage)
                HubMessageType.STREAM_INVOCATION -> writeStreamInvocationMessage(hubMessage as StreamInvocationMessage)
                HubMessageType.CANCEL_INVOCATION -> writeCancelInvocationMessage(hubMessage as CancelInvocationMessage)
                HubMessageType.PING -> writePingMessage(hubMessage as PingMessage)
                HubMessageType.CLOSE -> writeCloseMessage(hubMessage as CloseMessage)
                else -> throw RuntimeException(
                    String.format(
                        "Unexpected message type: %d",
                        messageType.value,
                    ),
                )
            }
            val length = message.size
            val header: List<Byte> = getLengthHeader(length)
            val messageWithHeader = ByteArray(header.size + length)
            val headerSize = header.size

            // Write the length header, then all of the bytes of the original message
            for (i in 0 until headerSize) {
                messageWithHeader[i] = header[i]
            }
            for (i in 0 until length) {
                messageWithHeader[i + headerSize] = message[i]
            }
            ByteBuffer.wrap(messageWithHeader)
        } catch (ex: MessagePackException) {
            throw RuntimeException("Error writing MessagePack data.", ex)
        } catch (ex: IOException) {
            throw RuntimeException("Error writing MessagePack data.", ex)
        }
    }

    @Throws(IOException::class)
    private fun createInvocationMessage(
        unpacker: MessageUnpacker,
        binder: InvocationBinder,
        itemCount: Int,
        payload: ByteBuffer,
    ): HubMessage {
        val headers = readHeaders(unpacker)

        // invocationId may be nil
        var invocationId: String? = null
        if (!unpacker.tryUnpackNil()) {
            invocationId = unpacker.unpackString()
        }

        // For MsgPack, we represent an empty invocation ID as an empty string,
        // so we need to normalize that to "null", which is what indicates a non-blocking invocation.
        if (invocationId == null || invocationId.isEmpty()) {
            invocationId = null
        }
        val target = unpacker.unpackString()
        var arguments: Array<Any?>? = null
        arguments = try {
            val types = binder.getParameterTypes(target)
            bindArguments(unpacker, types, payload)
        } catch (ex: Exception) {
            return InvocationBindingFailureMessage(invocationId, target, ex)
        }
        var streams: Collection<String?>? = null
        // Older implementations may not send the streamID array
        if (itemCount > 5) {
            streams = readStreamIds(unpacker)
        }
        return InvocationMessage(headers, invocationId, target, arguments, streams)
    }

    @Throws(IOException::class)
    private fun createStreamItemMessage(
        unpacker: MessageUnpacker,
        binder: InvocationBinder,
        payload: ByteBuffer,
    ): HubMessage {
        val headers = readHeaders(unpacker)
        val invocationId = unpacker.unpackString()
        val value: Any?
        value = try {
            val itemType = binder.getReturnType(invocationId)
            readValue(unpacker, itemType, payload, true)
        } catch (ex: Exception) {
            return StreamBindingFailureMessage(invocationId, ex)
        }
        return StreamItem(headers, invocationId, value)
    }

    @Throws(IOException::class)
    private fun createCompletionMessage(
        unpacker: MessageUnpacker,
        binder: InvocationBinder,
        payload: ByteBuffer,
    ): HubMessage {
        val headers = readHeaders(unpacker)
        val invocationId = unpacker.unpackString()
        val resultKind = unpacker.unpackInt()
        var error: String? = null
        var result: Any? = null
        when (resultKind) {
            ERROR_RESULT -> error = unpacker.unpackString()
            VOID_RESULT -> {}
            NON_VOID_RESULT -> {
                val itemType = binder.getReturnType(invocationId)
                result = readValue(unpacker, itemType, payload, true)
            }

            else -> throw RuntimeException("Invalid invocation result kind.")
        }
        return CompletionMessage(headers, invocationId, result, error)
    }

    @Throws(IOException::class)
    private fun createStreamInvocationMessage(
        unpacker: MessageUnpacker,
        binder: InvocationBinder,
        itemCount: Int,
        payload: ByteBuffer,
    ): HubMessage {
        val headers = readHeaders(unpacker)
        val invocationId = unpacker.unpackString()
        val target = unpacker.unpackString()
        var arguments: Array<Any?>? = null
        arguments = try {
            val types = binder.getParameterTypes(target)
            bindArguments(unpacker, types, payload)
        } catch (ex: Exception) {
            return InvocationBindingFailureMessage(invocationId, target, ex)
        }
        val streams = readStreamIds(unpacker)
        return StreamInvocationMessage(headers, invocationId, target, arguments, streams)
    }

    @Throws(IOException::class)
    private fun createCancelInvocationMessage(unpacker: MessageUnpacker): HubMessage {
        val headers = readHeaders(unpacker)
        val invocationId = unpacker.unpackString()
        return CancelInvocationMessage(headers, invocationId)
    }

    @Throws(IOException::class)
    private fun createCloseMessage(unpacker: MessageUnpacker, itemCount: Int): HubMessage {
        // error may be nil
        var error: String? = null
        if (!unpacker.tryUnpackNil()) {
            error = unpacker.unpackString()
        }
        var allowReconnect = false
        if (itemCount > 2) {
            allowReconnect = unpacker.unpackBoolean()
        }
        return CloseMessage(error, allowReconnect)
    }

    @Throws(IOException::class)
    private fun writeInvocationMessage(message: InvocationMessage): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(6)
        packer.packInt(message.messageType.value)
        writeHeaders(message.headers, packer)
        val invocationId = message.invocationId
        if (invocationId != null && !invocationId.isEmpty()) {
            packer.packString(invocationId)
        } else {
            packer.packNil()
        }
        packer.packString(message.target)
        val arguments = message.arguments
        packer.packArrayHeader(arguments.size)
        for (o in arguments) {
            writeValue(o, packer)
        }
        writeStreamIds(message.streamIds, packer)
        packer.flush()
        val content = packer.toByteArray()
        packer.close()
        return content
    }

    @Throws(IOException::class)
    private fun writeStreamItemMessage(message: StreamItem): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(4)
        packer.packInt(message.messageType.value)
        writeHeaders(message.headers, packer)
        packer.packString(message.invocationId)
        writeValue(message.item, packer)
        packer.flush()
        val content = packer.toByteArray()
        packer.close()
        return content
    }

    @Throws(IOException::class)
    private fun writeCompletionMessage(message: CompletionMessage): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        val resultKind =
            if (message.error != null) ERROR_RESULT else if (message.result != null) NON_VOID_RESULT else VOID_RESULT
        packer.packArrayHeader(4 + if (resultKind != VOID_RESULT) 1 else 0)
        packer.packInt(message.messageType.value)
        writeHeaders(message.headers, packer)
        packer.packString(message.invocationId)
        packer.packInt(resultKind)
        when (resultKind) {
            ERROR_RESULT -> packer.packString(message.error)
            NON_VOID_RESULT -> writeValue(message.result, packer)
        }
        packer.flush()
        val content = packer.toByteArray()
        packer.close()
        return content
    }

    @Throws(IOException::class)
    private fun writeStreamInvocationMessage(message: StreamInvocationMessage): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(6)
        packer.packInt(message.messageType.value)
        writeHeaders(message.headers, packer)
        packer.packString(message.invocationId)
        packer.packString(message.target)
        val arguments = message.arguments
        packer.packArrayHeader(arguments.size)
        for (o in arguments) {
            writeValue(o, packer)
        }
        writeStreamIds(message.streamIds, packer)
        packer.flush()
        val content = packer.toByteArray()
        packer.close()
        return content
    }

    @Throws(IOException::class)
    private fun writeCancelInvocationMessage(message: CancelInvocationMessage): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packInt(message.messageType.value)
        writeHeaders(message.headers, packer)
        packer.packString(message.invocationId)
        packer.flush()
        val content = packer.toByteArray()
        packer.close()
        return content
    }

    @Throws(IOException::class)
    private fun writePingMessage(message: PingMessage): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(1)
        packer.packInt(message.messageType.value)
        packer.flush()
        val content = packer.toByteArray()
        packer.close()
        return content
    }

    @Throws(IOException::class)
    private fun writeCloseMessage(message: CloseMessage): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packInt(message.messageType.value)
        val error = message.error
        if (error != null && !error.isEmpty()) {
            packer.packString(error)
        } else {
            packer.packNil()
        }
        packer.packBoolean(message.allowReconnect)
        packer.flush()
        val content = packer.toByteArray()
        packer.close()
        return content
    }

    @Throws(IOException::class)
    private fun readHeaders(unpacker: MessageUnpacker): Map<String, String>? {
        val headerCount = unpacker.unpackMapHeader()
        return if (headerCount > 0) {
            val headers: MutableMap<String, String> =
                HashMap()
            for (i in 0 until headerCount) {
                headers[unpacker.unpackString()] = unpacker.unpackString()
            }
            headers
        } else {
            null
        }
    }

    @Throws(IOException::class)
    private fun writeHeaders(headers: Map<String, String>?, packer: MessagePacker) {
        if (headers != null) {
            packer.packMapHeader(headers.size)
            for (k in headers.keys) {
                packer.packString(k)
                packer.packString(headers[k])
            }
        } else {
            packer.packMapHeader(0)
        }
    }

    @Throws(IOException::class)
    private fun readStreamIds(unpacker: MessageUnpacker): Collection<String?>? {
        val streamCount = unpacker.unpackArrayHeader()
        var streams: MutableCollection<String?>? = null
        if (streamCount > 0) {
            streams = ArrayList()
            for (i in 0 until streamCount) {
                streams.add(unpacker.unpackString())
            }
        }
        return streams
    }

    @Throws(IOException::class)
    private fun writeStreamIds(streamIds: Collection<String>?, packer: MessagePacker) {
        if (streamIds != null) {
            packer.packArrayHeader(streamIds.size)
            for (s in streamIds) {
                packer.packString(s)
            }
        } else {
            packer.packArrayHeader(0)
        }
    }

    @Throws(IOException::class)
    private fun bindArguments(
        unpacker: MessageUnpacker,
        paramTypes: List<Type>,
        payload: ByteBuffer,
    ): Array<Any?> {
        val argumentCount = unpacker.unpackArrayHeader()
        if (paramTypes.size != argumentCount) {
            throw RuntimeException(
                String.format(
                    "Invocation provides %d argument(s) but target expects %d.",
                    argumentCount,
                    paramTypes.size,
                ),
            )
        }
        val arguments = arrayOfNulls<Any>(argumentCount)
        for (i in 0 until argumentCount) {
            arguments[i] = readValue(unpacker, paramTypes[i], payload, true)
        }
        return arguments
    }

    @Throws(IOException::class)
    private fun readValue(
        unpacker: MessageUnpacker,
        itemType: Type?,
        payload: ByteBuffer,
        outermostCall: Boolean,
    ): Any? {
        val itemClass = typeToClass(itemType)
        val messageFormat = unpacker.nextFormat
        val valueType = messageFormat.valueType
        val length: Int
        val readBytesStart: Long
        var item: Any? = null
        when (valueType) {
            ValueType.NIL -> {
                unpacker.unpackNil()
                return null
            }

            ValueType.BOOLEAN -> item = unpacker.unpackBoolean()
            ValueType.INTEGER -> when (messageFormat) {
                MessageFormat.UINT64 -> item = unpacker.unpackBigInteger()
                MessageFormat.INT64, MessageFormat.UINT32 -> item = unpacker.unpackLong()
                else -> {
                    item = unpacker.unpackInt()
                    // unpackInt could correspond to an int, short, char, or byte - cast those literally here
                    if (itemClass != null) {
                        if (itemClass == Short::class.java || itemClass == Short::class.javaPrimitiveType) {
                            item = item.toShort()
                        } else if (itemClass == Char::class.java || itemClass == Char::class.javaPrimitiveType) {
                            item = item.toShort().toChar()
                        } else if (itemClass == Byte::class.java || itemClass == Byte::class.javaPrimitiveType) {
                            item = item.toByte()
                        }
                    }
                }
            }

            ValueType.FLOAT -> item = unpacker.unpackDouble()
            ValueType.STRING -> {
                item = unpacker.unpackString()
                // ObjectMapper packs chars as Strings - correct back to char while unpacking if necessary
                if (itemClass != null && (itemClass == Char::class.javaPrimitiveType || itemClass == Char::class.java)) {
                    item = item!![0]
                }
            }

            ValueType.BINARY -> {
                length = unpacker.unpackBinaryHeader()
                val binaryValue = ByteArray(length)
                unpacker.readPayload(binaryValue)
                item = binaryValue
            }

            ValueType.ARRAY -> {
                readBytesStart = unpacker.totalReadBytes
                length = unpacker.unpackArrayHeader()
                var i = 0
                while (i < length) {
                    readValue(unpacker, Any::class.java, payload, false)
                    i++
                }
                return if (outermostCall) {
                    // Check how many bytes we've read, grab that from the payload, and deserialize with objectMapper
                    val payloadBytes = payload.array()
                    // If itemType was null, we were just in this method to advance the buffer. return null.
                    if (itemType == null) {
                        null
                    } else {
                        objectMapper.readValue<Any>(
                            payloadBytes,
                            payload.position() + readBytesStart.toInt(),
                            (unpacker.totalReadBytes - readBytesStart).toInt(),
                            typeFactory.constructType(itemType),
                        )
                    }
                } else {
                    // This is an inner call to readValue - we just need to read the right number of bytes
                    // We can return null, and the outermost call will know how many bytes to give to objectMapper.
                    null
                }
            }

            ValueType.MAP -> {
                readBytesStart = unpacker.totalReadBytes
                length = unpacker.unpackMapHeader()
                var i = 0
                while (i < length) {
                    readValue(unpacker, Any::class.java, payload, false)
                    readValue(unpacker, Any::class.java, payload, false)
                    i++
                }
                return if (outermostCall) {
                    // Check how many bytes we've read, grab that from the payload, and deserialize with objectMapper
                    val payloadBytes = payload.array()
                    val mapBytes = Arrays.copyOfRange(
                        payloadBytes,
                        payload.position() + readBytesStart.toInt(),
                        payload.position() + unpacker.totalReadBytes.toInt(),
                    )
                    // If itemType was null, we were just in this method to advance the buffer. return null.
                    if (itemType == null) {
                        null
                    } else {
                        objectMapper.readValue<Any>(
                            payloadBytes,
                            payload.position() + readBytesStart.toInt(),
                            (unpacker.totalReadBytes - readBytesStart).toInt(),
                            typeFactory.constructType(itemType),
                        )
                    }
                } else {
                    // This is an inner call to readValue - we just need to read the right number of bytes
                    // We can return null, and the outermost call will know how many bytes to give to objectMapper.
                    null
                }
            }

            ValueType.EXTENSION -> {
                val extension = unpacker.unpackExtensionTypeHeader()
                val extensionValue = ByteArray(extension.length)
                unpacker.readPayload(extensionValue)
                // TODO: Convert this to an object? Have to return
                //  stub object to prevent a crash,
                item = extensionValue
                return Any()
            }

            else -> return null
        }
        // If itemType was null, we were just in this method to advance the buffer. return null.
        if (itemType == null) {
            return null
        }
        // If we get here, the item isn't a map or a collection/array, so we use the Class to cast it
        return if (itemClass!!.isPrimitive) {
            toPrimitive(itemClass, item)
        } else {
            itemClass.cast(item)
        }
    }

    @Throws(IOException::class)
    private fun writeValue(o: Any, packer: MessagePacker) {
        packer.addPayload(objectMapper.writeValueAsBytes(o))
    }

    companion object {
        private const val ERROR_RESULT = 1
        private const val VOID_RESULT = 2
        private const val NON_VOID_RESULT = 3
    }
}
