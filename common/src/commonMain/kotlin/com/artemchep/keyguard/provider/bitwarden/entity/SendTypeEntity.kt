package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlin.enums.enumEntries
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(SendTypeEntitySerializer::class)
enum class SendTypeEntity(
    override val int: Int,
) : IntEnum {
    Text(0),
    File(1),
    Unknown(-1),
    ;

    companion object
}

object SendTypeEntitySerializer : KSerializer<SendTypeEntity> {
    override val descriptor = Int.serializer().descriptor

    private val choicesByInt =
        enumEntries<SendTypeEntity>()
            .filter { it != SendTypeEntity.Unknown }
            .associateBy { it.int }

    override fun serialize(
        encoder: Encoder,
        value: SendTypeEntity,
    ) {
        if (value == SendTypeEntity.Unknown) {
            throw SerializationException("Cannot serialize unknown send type.")
        }
        encoder.encodeInt(value.int)
    }

    override fun deserialize(decoder: Decoder): SendTypeEntity {
        val value = decoder.decodeInt()
        return choicesByInt[value] ?: SendTypeEntity.Unknown
    }
}

fun SendTypeEntity.Companion.of(
    model: BitwardenSend.Type,
) = when (model) {
    BitwardenSend.Type.None ->
        throw IllegalArgumentException("Unknown Send type!")

    BitwardenSend.Type.Text -> SendTypeEntity.Text
    BitwardenSend.Type.File -> SendTypeEntity.File
}

fun SendTypeEntity.domain() = when (this) {
    SendTypeEntity.Text -> BitwardenSend.Type.Text
    SendTypeEntity.File -> BitwardenSend.Type.File
    SendTypeEntity.Unknown -> error("Unknown send type cannot be converted to a domain model.")
}
