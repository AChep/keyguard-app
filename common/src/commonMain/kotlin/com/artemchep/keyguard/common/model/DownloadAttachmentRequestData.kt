package com.artemchep.keyguard.common.model

data class DownloadAttachmentRequestData(
    // ids
    val localCipherId: String,
    val remoteCipherId: String?,
    val attachmentId: String,
    // file info
    val url: String,
    val urlIsOneTime: Boolean,
    val name: String,
    val encryptionKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DownloadAttachmentRequestData

        if (localCipherId != other.localCipherId) return false
        if (remoteCipherId != other.remoteCipherId) return false
        if (attachmentId != other.attachmentId) return false
        if (url != other.url) return false
        if (urlIsOneTime != other.urlIsOneTime) return false
        if (!encryptionKey.contentEquals(other.encryptionKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = localCipherId.hashCode()
        result = 31 * result + (remoteCipherId?.hashCode() ?: 0)
        result = 31 * result + attachmentId.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + urlIsOneTime.hashCode()
        result = 31 * result + encryptionKey.contentHashCode()
        return result
    }
}
