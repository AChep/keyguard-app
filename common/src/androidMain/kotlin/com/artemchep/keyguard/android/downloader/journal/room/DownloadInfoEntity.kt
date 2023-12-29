package com.artemchep.keyguard.android.downloader.journal.room

import androidx.room.Embedded
import androidx.room.Entity
import com.artemchep.keyguard.ui.canRetry
import kotlinx.datetime.Instant

@Entity(
    primaryKeys = [
        "id",
    ],
)
data class DownloadInfoEntity(
    val id: String,
    val localCipherId: String,
    val remoteCipherId: String?,
    val attachmentId: String,
    val url: String,
    val urlIsOneTime: Boolean,
    val name: String,
    val createdDate: Instant,
    val revisionDate: Instant = createdDate,
    /**
     * Encryption key used to decrypt the source
     * url after downloading.
     */
    val encryptionKeyBase64: String? = null,
    /**
     * Last downloading / decryption error, you can use it
     * to find out if you can retry the downloading or if
     * it should be done manually.
     */
    @Embedded
    val error: Error? = null,
) {
    // must be data class
    data class AttachmentDownloadTag(
        val localCipherId: String, // to be able to find the right account for the item
        val remoteCipherId: String?,
        val attachmentId: String,
    )

    data class Error(
        val code: Int,
        val attempt: Int = 1,
        val message: String? = null,
    ) {
        fun canRetry() = code.canRetry() && attempt <= 3
    }
}

fun DownloadInfoEntity2.toEntity() = DownloadInfoEntity(
    id = id,
    localCipherId = localCipherId,
    remoteCipherId = remoteCipherId,
    attachmentId = attachmentId,
    url = url,
    urlIsOneTime = urlIsOneTime,
    name = name,
    createdDate = createdDate,
    revisionDate = revisionDate,
    encryptionKeyBase64 = encryptionKeyBase64,
    error = error?.toEntity(),
)

fun DownloadInfoEntity2.Error.toEntity() = DownloadInfoEntity.Error(
    code = code,
    attempt = attempt,
    message = message,
)

fun DownloadInfoEntity.toDomain() = DownloadInfoEntity2(
    id = id,
    localCipherId = localCipherId,
    remoteCipherId = remoteCipherId,
    attachmentId = attachmentId,
    url = url,
    urlIsOneTime = urlIsOneTime,
    name = name,
    createdDate = createdDate,
    revisionDate = revisionDate,
    encryptionKeyBase64 = encryptionKeyBase64,
    error = error?.toDomain(),
)

fun DownloadInfoEntity.Error.toDomain() = DownloadInfoEntity2.Error(
    code = code,
    attempt = attempt,
    message = message,
)
