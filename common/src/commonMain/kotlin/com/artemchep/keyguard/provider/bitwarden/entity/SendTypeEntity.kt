package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(SendTypeEntitySerializer::class)
enum class SendTypeEntity(
    override val int: Int,
) : IntEnum {
    Text(0),
    File(1),
    ;

    companion object
}

object SendTypeEntitySerializer : CommonEnumIntSerializer<SendTypeEntity>(SendTypeEntity::class)

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
}
