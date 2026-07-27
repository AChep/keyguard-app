package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonArray

abstract class SafeListSerializer<T>(
    private val elementSerializer: KSerializer<T>,
) : KSerializer<List<T>> {
    private val listSerializer: KSerializer<List<T>> = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor get() = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) =
        listSerializer.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return listSerializer.deserialize(decoder)
        return jsonDecoder.decodeJsonElement()
            .jsonArray
            .mapNotNull { element ->
                // Parse the cipher or skip it
                runCatching {
                    jsonDecoder.json.decodeFromJsonElement(elementSerializer, element)
                }
                    .onFailure(Throwable::throwIfFatalOrCancellation)
                    .getOrNull()
            }
    }
}

object CipherEntityListSerializer : SafeListSerializer<CipherEntity>(CipherEntity.serializer())
