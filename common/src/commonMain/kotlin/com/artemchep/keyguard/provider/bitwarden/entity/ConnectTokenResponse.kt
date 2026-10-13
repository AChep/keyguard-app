package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.provider.bitwarden.model.Login
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlin.time.Duration

@Serializable
data class ConnectTokenResponse(
    @JsonNames("kdf")
    @SerialName("Kdf")
    val kdfType: Int? = null,
    @JsonNames("kdfIterations")
    @SerialName("KdfIterations")
    val kdfIterationsCount: Int? = null,

    @JsonNames("key")
    @SerialName("Key")
    val key: String? = null,
    @JsonNames("forcePasswordReset")
    @SerialName("ForcePasswordReset")
    val forcePasswordReset: Boolean = false,
    @JsonNames("userDecryptionOptions")
    @SerialName("UserDecryptionOptions")
    val userDecryptionOptions: SsoDecryptionOptions? = null,
    /** Older servers report the Key Connector only here. */
    @JsonNames("keyConnectorUrl")
    @SerialName("KeyConnectorUrl")
    val keyConnectorUrl: String? = null,

    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val accessTokenType: String,

    @SerialName("expires_in")
    val accessTokenExpiresInSeconds: Long,
    @JsonNames("resetMasterPassword")
    @SerialName("ResetMasterPassword")
    val resetMasterPassword: Boolean? = null,
    @SerialName("refresh_token")
    val refreshToken: String,
    /**
     * Old versions of the app do not
     * have the scope.
     */
    @SerialName("scope")
    val scope: String? = null,
)

fun ConnectTokenResponse.toDomain() = Login(
    accessToken = accessToken,
    accessTokenType = accessTokenType,
    accessTokenExpiryDate = kotlin.run {
        val now = Clock.System.now()
        now + with(Duration) { accessTokenExpiresInSeconds.seconds }
    },
    refreshToken = refreshToken,
    scope = scope,
)
