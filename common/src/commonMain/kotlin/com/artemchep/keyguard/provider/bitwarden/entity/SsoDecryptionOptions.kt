package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SsoDecryptionOptions(
    @SerialName("HasMasterPassword") @JsonNames("hasMasterPassword")
    val hasMasterPassword: Boolean? = null,
    @SerialName("MasterPasswordUnlock") @JsonNames("masterPasswordUnlock")
    val masterPasswordUnlock: SsoMasterPasswordUnlock? = null,
    @SerialName("KeyConnectorOption") @JsonNames("keyConnectorOption")
    val keyConnectorOption: SsoKeyConnectorOption? = null,
)

@Serializable
data class SsoKeyConnectorOption(
    @SerialName("KeyConnectorUrl") @JsonNames("keyConnectorUrl")
    val keyConnectorUrl: String,
)

/** The master key that a Key Connector stores for the user. */
@Serializable
data class KeyConnectorUserKeyResponse(
    @SerialName("key") @JsonNames("Key")
    val key: String,
)

@Serializable
data class SsoMasterPasswordUnlock(
    @SerialName("Salt") @JsonNames("salt")
    val salt: String,
    @SerialName("Kdf") @JsonNames("kdf")
    val kdf: AccountPreLoginResponse.KdfSettings,
    @SerialName("MasterKeyWrappedUserKey")
    @JsonNames("masterKeyWrappedUserKey", "MasterKeyEncryptedUserKey", "masterKeyEncryptedUserKey")
    val wrappedUserKey: String? = null,
)

/** Login only needs identity and unlock metadata; an unenrolled account may have no keys yet. */
@Serializable
data class SsoProfileEntity(
    @SerialName("Id") @JsonNames("id")
    val id: String,
    @SerialName("Email") @JsonNames("email")
    val email: String,
    @SerialName("Key") @JsonNames("key")
    val key: String? = null,
    @SerialName("UsesKeyConnector") @JsonNames("usesKeyConnector")
    val usesKeyConnector: Boolean = false,
    @SerialName("ForcePasswordReset") @JsonNames("forcePasswordReset")
    val forcePasswordReset: Boolean = false,
)
