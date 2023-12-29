package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(CipherTypeEntitySerializer::class)
enum class CipherTypeEntity(
    override val int: Int,
) : IntEnum {
    Login(1),
    SecureNote(2),
    Card(3),
    Identity(4),
    ;

    companion object
}

object CipherTypeEntitySerializer :
    CommonEnumIntSerializer<CipherTypeEntity>(CipherTypeEntity::class)

fun CipherTypeEntity.Companion.of(
    model: BitwardenCipher.Type,
) = when (model) {
    BitwardenCipher.Type.Login -> CipherTypeEntity.Login
    BitwardenCipher.Type.SecureNote -> CipherTypeEntity.SecureNote
    BitwardenCipher.Type.Card -> CipherTypeEntity.Card
    BitwardenCipher.Type.Identity -> CipherTypeEntity.Identity
}

fun CipherTypeEntity.domain() = when (this) {
    CipherTypeEntity.Login -> BitwardenCipher.Type.Login
    CipherTypeEntity.SecureNote -> BitwardenCipher.Type.SecureNote
    CipherTypeEntity.Card -> BitwardenCipher.Type.Card
    CipherTypeEntity.Identity -> BitwardenCipher.Type.Identity
}
