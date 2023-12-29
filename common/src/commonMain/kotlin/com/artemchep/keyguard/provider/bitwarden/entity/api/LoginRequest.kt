package com.artemchep.keyguard.provider.bitwarden.entity.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    @SerialName("fido2Credentials")
    val fido2Credentials: List<LoginFido2CredentialsRequest>,
    @SerialName("uris")
    val uris: List<LoginUriRequest>,
    @SerialName("username")
    val username: String?,
    @SerialName("password")
    val password: String?,
    @SerialName("passwordRevisionDate")
    val passwordRevisionDate: Instant?,
    @SerialName("totp")
    val totp: String?,
) {
    companion object
}

fun LoginRequest.Companion.of(
    model: BitwardenCipher.Login,
) = kotlin.run {
    val fido2CredentialsRequests = model
        .fido2Credentials
        .map(LoginFido2CredentialsRequest::of)
    val urisRequests = model
        .uris
        .map(LoginUriRequest::of)
    LoginRequest(
        fido2Credentials = fido2CredentialsRequests,
        uris = urisRequests,
        username = model.username,
        password = model.password,
        passwordRevisionDate = model.passwordRevisionDate,
        totp = model.totp,
    )
}
