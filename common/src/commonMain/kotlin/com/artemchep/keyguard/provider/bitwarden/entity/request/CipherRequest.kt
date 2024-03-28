package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.entity.CipherRepromptTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.api.FieldApi
import com.artemchep.keyguard.provider.bitwarden.entity.api.IdentityRequest
import com.artemchep.keyguard.provider.bitwarden.entity.api.LoginRequest
import com.artemchep.keyguard.provider.bitwarden.entity.api.SecureNoteRequest
import com.artemchep.keyguard.provider.bitwarden.entity.api.of
import com.artemchep.keyguard.provider.bitwarden.entity.of
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherRequest(
    @SerialName("key")
    val key: String?,
    @SerialName("type")
    val type: CipherTypeEntity,
    @SerialName("organizationId")
    val organizationId: String?,
    @SerialName("folderId")
    val folderId: String?,
    @SerialName("name")
    val name: String?,
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
    @SerialName("reprompt")
    val reprompt: CipherRepromptTypeEntity,
) {
    companion object
}

fun CipherRequest.Companion.of(
    model: BitwardenCipher,
    folders: Map<String, String?>,
) = kotlin.run {
    val type = CipherTypeEntity.of(model.type)
    val reprompt = CipherRepromptTypeEntity.of(model.reprompt)
    val login = model.login
        ?.let { login ->
            LoginRequest.of(login)
        }
    val secureNote = model.secureNote
        ?.let(SecureNoteRequest::of)
    val card = model.card
        ?.let(CardRequest::of)
    val identity = model.identity
        ?.let(IdentityRequest::of)
    val fields = model.fields
        .map { field ->
            FieldApi.of(field)
        }
        .takeUnless { it.isEmpty() }
    val passwordHistory = model.login?.passwordHistory.orEmpty()
        .map { passwordHistory ->
            PasswordHistoryRequest.of(passwordHistory)
        }
        .takeUnless { it.isEmpty() }
    val (attachments, attachments2) = kotlin.run {
        val tmp = model.attachments
            .mapNotNull { it as? BitwardenCipher.Attachment.Remote }
            .associateBy { it.id }
        if (tmp.isEmpty()) {
            return@run null to null
        }
        val attachments = tmp
            .mapValues { it.value.fileName }
        val attachments2 = tmp
            .mapValues {
                AttachmentRequest.of(it.value)
            }
        attachments to attachments2
    }

    val keyBase64 = model.keyBase64
    CipherRequest(
        key = keyBase64,
        type = type,
        organizationId = model.organizationId,
        folderId = model.folderId
            // Folders might not include the folder id of this cipher, and
            // that could be cause because the folder was deleted.
            ?.let { folders[it] },
        name = model.name,
        notes = model.notes,
        favorite = model.favorite,
        login = login,
        secureNote = secureNote,
        card = card,
        identity = identity,
        fields = fields,
        passwordHistory = passwordHistory,
        attachments = attachments,
        attachments2 = attachments2,
        lastKnownRevisionDate = model.service.remote?.revisionDate,
        reprompt = reprompt,
    )
}
