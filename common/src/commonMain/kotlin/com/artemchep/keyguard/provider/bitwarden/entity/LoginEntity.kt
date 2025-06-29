package com.artemchep.keyguard.provider.bitwarden.entity

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class LoginEntity(
    @JsonNames("uris")
    @SerialName("Uris")
    val uris: List<LoginUriEntity>? = null,
    @JsonNames("username")
    @SerialName("Username")
    val username: String? = null,
    @JsonNames("password")
    @SerialName("Password")
    val password: String? = null,
    @JsonNames("passwordRevisionDate")
    @SerialName("PasswordRevisionDate")
    val passwordRevisionDate: Instant? = null,
    @JsonNames("totp")
    @SerialName("Totp")
    val totp: String? = null,
    @JsonNames("fido2Credentials")
    @SerialName("Fido2Credentials")
    val fido2Credentials: List<LoginFido2CredentialsEntity>? = null,
)
