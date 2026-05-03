package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.transformBase64
import com.artemchep.keyguard.provider.bitwarden.crypto.transformString
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CipherAttachmentCreateRequest(
    @SerialName("key")
    val key: String,
    @SerialName("fileName")
    val fileName: String,
    @SerialName("fileSize")
    val fileSize: Long,
    @SerialName("adminRequest")
    val adminRequest: Boolean? = null,
    @SerialName("lastKnownRevisionDate")
    val lastKnownRevisionDate: Instant? = null,
) {
    companion object
}

fun CipherAttachmentCreateRequest.Companion.of(
    cipher: BitwardenCipher,
    attachment: BitwardenCipher.Attachment.Local,
    itemCrypto: BitwardenCrCta,
) = CipherAttachmentCreateRequest(
    key = itemCrypto.transformBase64(
        requireNotNull(attachment.keyBase64) {
            "A local cipher attachment requires an encryption key."
        },
    ),
    fileName = itemCrypto.transformString(attachment.fileName),
    fileSize = requireNotNull(attachment.pendingUpload?.encryptedSize ?: attachment.size) {
        "A local cipher attachment requires a file size."
    },
    adminRequest = false,
    lastKnownRevisionDate = cipher.service.remote?.revisionDate,
)
