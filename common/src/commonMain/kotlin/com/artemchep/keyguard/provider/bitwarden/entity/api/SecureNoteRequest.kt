package com.artemchep.keyguard.provider.bitwarden.entity.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.SecureNoteTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.of
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SecureNoteRequest(
    @SerialName("type")
    val type: SecureNoteTypeEntity,
)

fun SecureNoteRequest.Companion.of(
    model: BitwardenCipher.SecureNote,
) = kotlin.run {
    SecureNoteRequest(
        type = model.type.let(SecureNoteTypeEntity::of),
    )
}
