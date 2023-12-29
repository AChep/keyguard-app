package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(CipherRepromptTypeEntitySerializer::class)
enum class CipherRepromptTypeEntity(
    override val int: Int,
) : IntEnum {
    None(0),
    Password(1),
    ;

    companion object
}

object CipherRepromptTypeEntitySerializer :
    CommonEnumIntSerializer<CipherRepromptTypeEntity>(CipherRepromptTypeEntity::class)

fun CipherRepromptTypeEntity.Companion.of(
    model: BitwardenCipher.RepromptType,
) = when (model) {
    BitwardenCipher.RepromptType.None -> CipherRepromptTypeEntity.None
    BitwardenCipher.RepromptType.Password -> CipherRepromptTypeEntity.Password
}

fun CipherRepromptTypeEntity.domain() = when (this) {
    CipherRepromptTypeEntity.None -> BitwardenCipher.RepromptType.None
    CipherRepromptTypeEntity.Password -> BitwardenCipher.RepromptType.Password
}
