package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class CipherEntity(
    @JsonNames("id")
    @SerialName("Id")
    val id: String,
    @JsonNames("organizationId")
    @SerialName("OrganizationId")
    val organizationId: String? = null,
    @JsonNames("organizationUseTotp")
    @SerialName("OrganizationUseTotp")
    val organizationUseTotp: Boolean,
    @JsonNames("folderId")
    @SerialName("FolderId")
    val folderId: String? = null,
    @JsonNames("userId")
    @SerialName("UserId")
    val userId: String? = null,
    @JsonNames("edit")
    @SerialName("Edit")
    val edit: Boolean = true,
    @JsonNames("viewPassword")
    @SerialName("ViewPassword")
    val viewPassword: Boolean = true,
    @JsonNames("favorite")
    @SerialName("Favorite")
    val favorite: Boolean = false,
    @JsonNames("revisionDate")
    @SerialName("RevisionDate")
    val revisionDate: Instant,
    @JsonNames("creationDate")
    @SerialName("CreationDate")
    val creationDate: Instant? = null,
    @JsonNames("type")
    @SerialName("Type")
    val type: CipherTypeEntity = CipherTypeEntity.Login,
    @JsonNames("sizeName")
    @SerialName("SizeName")
    val sizeName: String? = null,
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("notes")
    @SerialName("Notes")
    val notes: String? = null,
    @JsonNames("login")
    @SerialName("Login")
    val login: LoginEntity? = null,
    @JsonNames("secureNote")
    @SerialName("SecureNote")
    val secureNote: SecureNoteEntity? = null,
    @JsonNames("card")
    @SerialName("Card")
    val card: CardEntity? = null,
    @JsonNames("identity")
    @SerialName("Identity")
    val identity: IdentityEntity? = null,
    @JsonNames("fields")
    @SerialName("Fields")
    val fields: List<FieldEntity>? = null,
    @JsonNames("attachments")
    @SerialName("Attachments")
    val attachments: List<AttachmentEntity>? = null,
    @JsonNames("passwordHistory")
    @SerialName("PasswordHistory")
    val passwordHistory: List<PasswordHistoryEntity>? = null,
    @JsonNames("collectionIds")
    @SerialName("CollectionIds")
    val collectionIds: List<String>? = null,
    @JsonNames("deletedDate")
    @SerialName("DeletedDate")
    val deletedDate: Instant? = null,
    @JsonNames("reprompt")
    @SerialName("Reprompt")
    val reprompt: CipherRepromptTypeEntity = CipherRepromptTypeEntity.None,
)
