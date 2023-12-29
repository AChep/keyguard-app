package com.artemchep.keyguard.provider.bitwarden.entity.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.UriMatchTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.of
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginUriRequest(
    @SerialName("uri")
    val uri: String,
    @SerialName("match")
    val match: UriMatchTypeEntity?,
) {
    companion object
}

fun LoginUriRequest.Companion.of(
    model: BitwardenCipher.Login.Uri,
) = kotlin.run {
    LoginUriRequest(
        uri = model.uri.orEmpty(),
        match = model.match?.let(UriMatchTypeEntity::of),
    )
}
