package com.artemchep.keyguard.provider.bitwarden.entity.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.FieldTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LinkedIdTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.of
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FieldApi(
    @SerialName("type")
    val type: FieldTypeEntity,
    @SerialName("name")
    val name: String?,
    @SerialName("value")
    val value: String?,
    @SerialName("linkedId")
    val linkedId: LinkedIdTypeEntity?,
) {
    companion object
}

fun FieldApi.Companion.of(
    model: BitwardenCipher.Field,
) = kotlin.run {
    val type = FieldTypeEntity.of(model.type)
    val linkedId = model.linkedId
        ?.let {
            LinkedIdTypeEntity.of(it)
        }
    FieldApi(
        type = type,
        name = model.name,
        value = model.value,
        linkedId = linkedId,
    )
}
