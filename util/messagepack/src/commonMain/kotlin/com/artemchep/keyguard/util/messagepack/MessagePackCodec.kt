package com.artemchep.keyguard.util.messagepack

import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPackNullableDynamicSerializer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object MessagePackCodec {
    fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray = MsgPack.encodeToByteArray(serializer, value)

    fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): T = MsgPack.decodeFromByteArray(deserializer, bytes)

    fun encodeDynamic(
        value: Any?,
    ): ByteArray = MsgPack.encodeToByteArray(
        serializer = MsgPackNullableDynamicSerializer,
        value = value,
    )

    fun decodeDynamic(
        bytes: ByteArray,
    ): Any? = MsgPack.decodeFromByteArray(
        deserializer = MsgPackNullableDynamicSerializer,
        bytes = bytes,
    )
}

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> toJsonPrimitive()
    is String -> JsonPrimitive(this)
    is ByteArray -> JsonPrimitive(decodeToString())
    is Array<*> -> JsonArray(
        map {
            it.toJsonElement()
        },
    )
    is Iterable<*> -> JsonArray(
        map {
            it.toJsonElement()
        },
    )
    is Map<*, *> -> JsonObject(
        entries.associate { (key, value) ->
            key.toString() to value.toJsonElement()
        },
    )
    else -> JsonPrimitive(toString())
}

fun JsonElement.toMessagePackDynamic(): Any? = when (this) {
    JsonNull -> null
    is JsonArray -> map {
        it.toMessagePackDynamic()
    }
    is JsonObject -> entries.associate { (key, value) ->
        key to value.toMessagePackDynamic()
    }
    is JsonPrimitive -> toMessagePackDynamic()
}

private fun Number.toJsonPrimitive(): JsonPrimitive = when (this) {
    is Float -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    else -> JsonPrimitive(toInt())
}

private fun JsonPrimitive.toMessagePackDynamic(): Any? = when {
    isString -> content
    booleanOrNull != null -> booleanOrNull
    longOrNull != null -> longOrNull
    doubleOrNull != null -> doubleOrNull
    else -> contentOrNull
}
