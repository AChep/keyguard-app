package com.artemchep.keyguard.android.downloader.journal.room

import com.artemchep.keyguard.common.util.canRetry
import kotlin.time.Instant

data class DownloadInfoEntity2(
    val id: String,
    val localCipherId: String,
    val remoteCipherId: String?,
    val attachmentId: String,
    val url: String,
    val urlIsOneTime: Boolean,
    val name: String,
    val hash: String? = null,
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
