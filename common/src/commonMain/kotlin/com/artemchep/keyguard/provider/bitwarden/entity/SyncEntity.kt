package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SyncEntity(
    @JsonNames("domains")
    @SerialName("Domains")
    val domains: DomainsEntity? = null,
    @JsonNames("ciphers")
    @SerialName("Ciphers")
    val ciphers: List<CipherEntity>? = null,
    @JsonNames("folders")
    @SerialName("Folders")
    val folders: List<FolderEntity>? = null,
    @JsonNames("collections")
    @SerialName("Collections")
    val collections: List<CollectionEntity>? = null,
    @JsonNames("profile")
    @SerialName("Profile")
    val profile: ProfileEntity,
    @JsonNames("sends")
    @SerialName("Sends")
    val sends: List<SendEntity>? = null,
    @JsonNames("unofficialServer")
    @SerialName("UnofficialServer")
    val unofficialServer: Boolean? = false,
)