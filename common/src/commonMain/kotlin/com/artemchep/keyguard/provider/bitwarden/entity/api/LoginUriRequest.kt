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
    @SerialName("uriChecksum")
    val uriChecksum: String?,
    @SerialName("match")
    val match: UriMatchTypeEntity?,
) {
    companion object
}

fun LoginUriRequest.Companion.of(
    model: BitwardenCipher.Login.Uri,
) = kotlin.run {
    LoginUriRequest(
        uri = requireNotNull(model.uri) { "Login URI request must have a non-null URI!" },
        uriChecksum = model.uriChecksumBase64,
        match = model.match?.let(UriMatchTypeEntity::of),
    )
}
