package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNames

@kotlinx.serialization.Serializable
data class OrganizationEntity(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("key")
    @SerialName("Key")
    val key: String,
    @JsonNames("privateKey")
    @SerialName("PrivateKey")
    val privateKey: String? = null,
    @JsonNames("name")
    @SerialName("Name")
    val name: String,
    @JsonNames("avatarColor")
    @SerialName("AvatarColor")
    val avatarColor: String? = null,
    @JsonNames("status")
    @SerialName("Status")
    val status: OrganizationUserStatusTypeEntity,
    @JsonNames("type")
    @SerialName("Type")
    val type: OrganizationUserTypeEntity,
    @JsonNames("enabled")
    @SerialName("Enabled")
    val enabled: Boolean = true,
//    val usePolicies: Boolean,
//    val useGroups: Boolean,
//    val useDirectory: Boolean,
//    val useEvents: Boolean,
//    val useTotp: Boolean,
//    val use2fa: Boolean,
//    val useApi: Boolean,
//    val useSso: Boolean,
//    val useResetPassword: Boolean,
    @JsonNames("selfHost")
    @SerialName("SelfHost")
    val selfHost: Boolean = false,
    @JsonNames("usersGetPremium")
    @SerialName("UsersGetPremium")
    val usersGetPremium: Boolean = false,
    @JsonNames("seats")
    @SerialName("Seats")
    val seats: Int = 0,
//    val maxCollections: Int,
//    val maxStorageGb: Int?,
//    val ssoBound: Boolean,
//    val identifier: String,
//    // val permissions: PermissionsApi,
//    val resetPasswordEnrolled: Boolean,
//    val userId: String,
//    val hasPublicAndPrivateKeys: Boolean,
//    val providerId: String,
//    val providerName: String,
//    val isProviderUser: Boolean,
)
