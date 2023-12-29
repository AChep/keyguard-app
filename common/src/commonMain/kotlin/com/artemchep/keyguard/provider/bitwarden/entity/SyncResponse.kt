package com.artemchep.keyguard.provider.bitwarden.entity

import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SyncResponse(
    @JsonNames("ciphers")
    @SerialName("Ciphers")
    val ciphers: List<SyncCipher>,
    @JsonNames("profile")
    @SerialName("Profile")
    val profile: SyncProfile,
)

@Serializable
data class SyncCipher(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("name")
    @SerialName("Name")
    val name: String,
    @JsonNames("login")
    @SerialName("Login")
    val login: SyncCipherLogin? = null,
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: Instant,
)

@Serializable
data class SyncCipherLogin(
    @JsonNames("password")
    @SerialName("Password")
    val password: String? = null,
    @JsonNames("username")
    @SerialName("Username")
    val username: String? = null,
)

@Serializable
data class SyncProfile(
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
    @JsonNames("premium")
    @SerialName("Premium")
    val premium: Boolean,
    @JsonNames("securityStamp")
    @SerialName("SecurityStamp")
    val securityStamp: String,
    @JsonNames("twoFactorEnabled")
    @SerialName("TwoFactorEnabled")
    val twoFactorEnabled: Boolean,
)

@Serializable
data class SyncSends(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("accessId")
    @SerialName("AccessId")
    val accessId: String,
    @JsonNames("key")
    @SerialName("Key")
    val key: String,
    @JsonNames("type")
    @SerialName("Type")
    val type: SendTypeEntity,
    @JsonNames("name")
    @SerialName("Name")
    val name: String,
    @JsonNames("notes")
    @SerialName("Notes")
    val notes: String? = null,
    @JsonNames("file")
    @SerialName("File")
    val file: SendFileEntity? = null,
    @JsonNames("text")
    @SerialName("Text")
    val text: SendTextEntity? = null,
    @JsonNames("accessCount")
    @SerialName("AccessCount")
    val accessCount: Int,
    @JsonNames("maxAccessCount")
    @SerialName("MaxAccessCount")
    val maxAccessCount: Int? = null,
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: Instant,
    @JsonNames("expirationDate")
    @SerialName("ExpirationDate")
    val expirationDate: Instant? = null,
    @JsonNames("deletionDate")
    @SerialName("DeletionDate")
    val deletionDate: Instant? = null,
    @JsonNames("password")
    @SerialName("Password")
    val password: String? = null,
    @JsonNames("disabled")
    @SerialName("Disabled")
    val disabled: Boolean,
    @JsonNames("hideEmail")
    @SerialName("HideEmail")
    val hideEmail: Boolean? = null,
)

data class SyncResponseToDomainResult(
    val profile: DProfile,
    val secrets: List<DSecret>,
)
