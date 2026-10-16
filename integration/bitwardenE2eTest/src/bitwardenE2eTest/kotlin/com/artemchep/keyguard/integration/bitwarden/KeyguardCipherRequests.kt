package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.postV2
import com.artemchep.keyguard.provider.bitwarden.api.builder.uploadCipherAttachment
import com.artemchep.keyguard.provider.bitwarden.crypto.makeCipherAttachmentCryptoKeyMaterial
import com.artemchep.keyguard.provider.bitwarden.entity.CipherRepromptTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.api.LoginRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherAttachmentCreateRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherRequest

/**
 * A Keyguard request for a login cipher with every field encrypted under [key]: the user
 * key, or the key of the organization [organizationId]. [encryptedFor] is the user id;
 * Vaultwarden rejects a cipher without it. Shared by the attachment and organization tests.
 */
internal fun VaultwardenApi.loginCipherRequest(
    key: ByteArray,
    encryptedFor: String,
    name: String,
    organizationId: String? = null,
): CipherRequest = CipherRequest(
    key = null,
    encryptedFor = encryptedFor,
    type = CipherTypeEntity.Login,
    organizationId = organizationId,
    folderId = null,
    name = accounts.encrypt(key, name),
    notes = null,
    favorite = false,
    login = LoginRequest(
        fido2Credentials = emptyList(),
        uris = emptyList(),
        username = accounts.encrypt(key, "alice"),
        password = accounts.encrypt(key, "hunter2"),
        passwordRevisionDate = null,
        totp = null,
    ),
    secureNote = null,
    card = null,
    identity = null,
    sshKey = null,
    fields = null,
    passwordHistory = null,
    attachments = null,
    attachments2 = null,
    lastKnownRevisionDate = null,
    archivedDate = null,
    reprompt = CipherRepromptTypeEntity.None,
)

/** An attachment that [uploadAttachment] created. */
internal class UploadedAttachment(
    val id: String,
    /** The new 64-byte attachment key. */
    val key: ByteArray,
    /** [key]'s [EncryptedFileFrame] of the content, as uploaded. */
    val frame: ByteArray,
)

/**
 * Reserves an attachment of [cipherId] through Keyguard's API and uploads [content] to it,
 * encrypted with a new attachment key. [ownerKey] encrypts the file name and that key: the
 * cipher's own key, or else its organization or user key.
 */
internal suspend fun VaultwardenApi.uploadAttachment(
    accessToken: String,
    cipherId: String,
    ownerKey: ByteArray,
    fileName: String,
    content: ByteArray,
): UploadedAttachment {
    val attachmentKey = cryptoGenerator.makeCipherAttachmentCryptoKeyMaterial()
    val frame = EncryptedFileFrame.encrypt(attachmentKey, content)
    val encryptedFileName = accounts.encrypt(ownerKey, fileName)
    val upload = env.api.ciphers.focus(cipherId).attachments.postV2(
        httpClient = httpClient,
        env = env,
        token = accessToken,
        body = CipherAttachmentCreateRequest(
            key = accounts.encrypt(ownerKey, attachmentKey),
            fileName = encryptedFileName,
            fileSize = frame.size.toLong(),
        ),
    )
    check(upload.anyCipherResponse.id == cipherId) { "The attachment was reserved for another cipher" }
    withUploadFile(frame) { path ->
        uploadCipherAttachment(
            httpClient = httpClient,
            env = env,
            token = accessToken,
            target = upload.uploadTarget,
            fileName = encryptedFileName,
            filePath = path,
            fileLength = frame.size.toLong(),
        )
    }
    return UploadedAttachment(id = upload.requiredAttachmentId, key = attachmentKey, frame = frame)
}
