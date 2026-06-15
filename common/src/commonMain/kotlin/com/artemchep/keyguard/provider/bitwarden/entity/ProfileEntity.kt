package com.artemchep.keyguard.provider.bitwarden.entity

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class ProfileEntity(
    @JsonNames("providerOrganizations")
    @SerialName("ProviderOrganizations")
    val providerOrganizations: List<OrganizationEntity>? = null,
    @JsonNames("premiumFromOrganization")
    @SerialName("PremiumFromOrganization")
    val premiumFromOrganization: Boolean = false,
    @JsonNames("forcePasswordReset")
    @SerialName("ForcePasswordReset")
    val forcePasswordReset: Boolean = false,
    @JsonNames("avatarColor")
    @SerialName("AvatarColor")
    val avatarColor: String? = null,
    @JsonNames("culture")
    @SerialName("Culture")
    val culture: String,
    @JsonNames("email")
    @SerialName("Email")
    val email: String,
    @JsonNames("emailVerified")
    @SerialName("EmailVerified")
    val emailVerified: Boolean,
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("key")
    @SerialName("Key")
    val key: String,
    @JsonNames("privateKey")
    @SerialName("PrivateKey")
    val privateKey: String,
    @JsonNames("accountKeys")
    @SerialName("AccountKeys")
    val accountKeys: AccountKeysEntity? = null,
    @JsonNames("masterPasswordHint")
    @SerialName("MasterPasswordHint")
    val masterPasswordHint: String? = null,
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("object")
    @SerialName("Object")
    val obj: String,
    @JsonNames("organizations")
    @SerialName("Organizations")
    val organizations: List<OrganizationEntity>? = null,
    @JsonNames("usesKeyConnector")
    @SerialName("UsesKeyConnector")
    val usesKeyConnector: Boolean = false,
    @JsonNames("premium")
    @SerialName("Premium")
    val premium: Boolean,
    @JsonNames("securityStamp")
    @SerialName("SecurityStamp")
    val securityStamp: String,
    @JsonNames("twoFactorEnabled")
    @SerialName("TwoFactorEnabled")
    val twoFactorEnabled: Boolean,
    @JsonNames("creationDate")
    @SerialName("CreationDate")
    val creationDate: Instant? = null,
    @JsonNames("keyConnectorUrl")
    @SerialName("KeyConnectorUrl")
    val keyConnectorUrl: String? = null,
)
