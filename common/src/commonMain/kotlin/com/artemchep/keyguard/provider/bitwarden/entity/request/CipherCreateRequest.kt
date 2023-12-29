package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherCreateRequest(
    @SerialName("cipher")
    val cipher: CipherRequest,
    @SerialName("collectionIds")
    val collectionIds: Set<String>,
)

fun CipherCreateRequest.Companion.of(
    model: BitwardenCipher,
    folders: Map<String, String?>,
) = kotlin.run {
    CipherCreateRequest(
        cipher = CipherRequest.of(
            model = model,
            folders = folders,
        ),
        collectionIds = model.collectionIds,
    )
}
