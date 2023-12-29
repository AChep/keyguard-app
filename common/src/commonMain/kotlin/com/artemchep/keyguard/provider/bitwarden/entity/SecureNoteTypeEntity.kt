package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(SecureNoteTypeEntitySerializer::class)
enum class SecureNoteTypeEntity(
    override val int: Int,
) : IntEnum {
    Generic(0),
    ;

    companion object
}

object SecureNoteTypeEntitySerializer :
    CommonEnumIntSerializer<SecureNoteTypeEntity>(SecureNoteTypeEntity::class)

fun SecureNoteTypeEntity.Companion.of(
    model: BitwardenCipher.SecureNote.Type,
) = when (model) {
    BitwardenCipher.SecureNote.Type.Generic -> SecureNoteTypeEntity.Generic
}

fun SecureNoteTypeEntity.domain() = when (this) {
    SecureNoteTypeEntity.Generic -> BitwardenCipher.SecureNote.Type.Generic
}
