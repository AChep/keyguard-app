package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.CommonEnumIntSerializer
import com.artemchep.keyguard.provider.bitwarden.entity.serializer.IntEnum
import kotlinx.serialization.Serializable

@Serializable(FieldTypeEntitySerializer::class)
enum class FieldTypeEntity(
    override val int: Int,
) : IntEnum {
    Text(0),
    Hidden(1),
    Boolean(2),
    Linked(3),
    ;

    companion object
}

object FieldTypeEntitySerializer : CommonEnumIntSerializer<FieldTypeEntity>(FieldTypeEntity::class)

fun FieldTypeEntity.Companion.of(
    model: BitwardenCipher.Field.Type,
) = when (model) {
    BitwardenCipher.Field.Type.Text -> FieldTypeEntity.Text
    BitwardenCipher.Field.Type.Hidden -> FieldTypeEntity.Hidden
    BitwardenCipher.Field.Type.Boolean -> FieldTypeEntity.Boolean
    BitwardenCipher.Field.Type.Linked -> FieldTypeEntity.Linked
}

fun FieldTypeEntity.Companion.of(
    model: DSecret.Field.Type,
) = when (model) {
    DSecret.Field.Type.Text -> FieldTypeEntity.Text
    DSecret.Field.Type.Hidden -> FieldTypeEntity.Hidden
    DSecret.Field.Type.Boolean -> FieldTypeEntity.Boolean
    DSecret.Field.Type.Linked -> FieldTypeEntity.Linked
}

fun FieldTypeEntity.domain() = when (this) {
    FieldTypeEntity.Text -> BitwardenCipher.Field.Type.Text
    FieldTypeEntity.Hidden -> BitwardenCipher.Field.Type.Hidden
    FieldTypeEntity.Boolean -> BitwardenCipher.Field.Type.Boolean
    FieldTypeEntity.Linked -> BitwardenCipher.Field.Type.Linked
}
