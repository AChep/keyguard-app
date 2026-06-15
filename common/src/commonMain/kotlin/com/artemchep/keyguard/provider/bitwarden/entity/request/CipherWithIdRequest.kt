package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.CipherRepromptTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.api.FieldApi
import com.artemchep.keyguard.provider.bitwarden.entity.api.IdentityRequest
import com.artemchep.keyguard.provider.bitwarden.entity.api.LoginRequest
import com.artemchep.keyguard.provider.bitwarden.entity.api.SecureNoteRequest
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherWithIdRequest(
    @SerialName("id")
    val id: String,
    @SerialName("key")
    val key: String?,
    @SerialName("encryptedFor")
    val encryptedFor: String? = null,
    @SerialName("type")
    val type: CipherTypeEntity,
    @SerialName("organizationId")
    val organizationId: String?,
    @SerialName("folderId")
    val folderId: String?,
    @SerialName("name")
    val name: String,
    @SerialName("notes")
    val notes: String?,
    @SerialName("favorite")
    val favorite: Boolean,
    @SerialName("login")
    val login: LoginRequest?,
    @SerialName("secureNote")
    val secureNote: SecureNoteRequest?,
    @SerialName("card")
    val card: CardRequest?,
    @SerialName("identity")
    val identity: IdentityRequest?,
    @SerialName("sshKey")
    val sshKey: SshKeyRequest?,
    @SerialName("fields")
    val fields: List<FieldApi>?,
    @SerialName("passwordHistory")
    val passwordHistory: List<PasswordHistoryRequest>?,
    @SerialName("attachments")
    val attachments: Map<String, String>?,
    @SerialName("attachments2")
    val attachments2: Map<String, AttachmentRequest>?,
    @SerialName("lastKnownRevisionDate")
    val lastKnownRevisionDate: Instant?,
    @SerialName("archivedDate")
    val archivedDate: Instant?,
    @SerialName("reprompt")
    val reprompt: CipherRepromptTypeEntity,
) {
    companion object
}

fun CipherWithIdRequest.Companion.of(
    id: String,
    cipher: CipherRequest,
) = CipherWithIdRequest(
    id = id,
    key = cipher.key,
    encryptedFor = cipher.encryptedFor,
    type = cipher.type,
    organizationId = cipher.organizationId,
    folderId = cipher.folderId,
    name = cipher.name,
    notes = cipher.notes,
    favorite = cipher.favorite,
    login = cipher.login,
    secureNote = cipher.secureNote,
    card = cipher.card,
    identity = cipher.identity,
    sshKey = cipher.sshKey,
    fields = cipher.fields,
    passwordHistory = cipher.passwordHistory,
    attachments = cipher.attachments,
    attachments2 = cipher.attachments2,
    lastKnownRevisionDate = cipher.lastKnownRevisionDate,
    archivedDate = cipher.archivedDate,
    reprompt = cipher.reprompt,
)

fun CipherWithIdRequest.Companion.of(
    model: BitwardenCipher,
    folders: Map<String, String?>,
    encryptedFor: String,
): CipherWithIdRequest {
    val remoteId = requireNotNull(model.service.remote?.id) {
        "Can not create a bulk-share cipher request for a local-only cipher!"
    }
    val cipher = CipherRequest.of(
        model = model,
        folders = folders,
        encryptedFor = encryptedFor,
    )
    return of(
        id = remoteId,
        cipher = cipher,
    )
}
