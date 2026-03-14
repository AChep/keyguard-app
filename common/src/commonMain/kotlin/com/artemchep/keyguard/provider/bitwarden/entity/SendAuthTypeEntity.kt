package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(SendAuthTypeEntitySerializer::class)
enum class SendAuthTypeEntity(
    override val int: Int,
) : IntEnum {
    Email(0),
    Password(1),
    None(2),
    ;

    companion object
}

object SendAuthTypeEntitySerializer :
    CommonEnumIntSerializer<SendAuthTypeEntity>(SendAuthTypeEntity::class)

fun SendAuthTypeEntity.domain() = when (this) {
    SendAuthTypeEntity.Email -> BitwardenSend.AuthType.Email
    SendAuthTypeEntity.Password -> BitwardenSend.AuthType.Password
    SendAuthTypeEntity.None -> BitwardenSend.AuthType.None
}

fun SendAuthTypeEntity.Companion.of(
    model: BitwardenSend.AuthType,
) = when (model) {
    BitwardenSend.AuthType.Email -> SendAuthTypeEntity.Email
    BitwardenSend.AuthType.Password -> SendAuthTypeEntity.Password
    BitwardenSend.AuthType.None -> SendAuthTypeEntity.None
}

fun SendAuthTypeEntity.Companion.of(
    model: DSend.AuthType,
) = when (model) {
    DSend.AuthType.Email -> SendAuthTypeEntity.Email
    DSend.AuthType.Password -> SendAuthTypeEntity.Password
    DSend.AuthType.None -> SendAuthTypeEntity.None
}
