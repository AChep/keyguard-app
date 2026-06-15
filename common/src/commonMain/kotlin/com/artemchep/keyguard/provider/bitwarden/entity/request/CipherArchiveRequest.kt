package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.serialization.Serializable

@Serializable
data class CipherArchiveRequest(
    val ids: List<String>,
)

fun CipherArchiveRequest.Companion.of(
    models: List<BitwardenCipher>,
) = run {
    val ids = models
        .mapNotNull { it.service.remote?.id }
    CipherArchiveRequest(
        ids = ids,
    )
}
