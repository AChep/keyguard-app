package com.artemchep.keyguard.provider.bitwarden.api.entity

import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CollectionEntity
import com.artemchep.keyguard.provider.bitwarden.entity.DomainsEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SyncProfile
import com.artemchep.keyguard.provider.bitwarden.entity.SyncSends
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SyncResponse(
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
    val profile: SyncProfile,
    @JsonNames("sends")
    @SerialName("Sends")
    val sends: List<SyncSends>? = null,
    @JsonNames("unofficialServer")
    @SerialName("UnofficialServer")
    val unofficialServer: Boolean? = false,
)
