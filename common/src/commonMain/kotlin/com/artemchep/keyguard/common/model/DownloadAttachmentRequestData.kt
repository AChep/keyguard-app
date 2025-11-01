package com.artemchep.keyguard.common.model

data class DownloadAttachmentRequestData(
    // ids
    val localCipherId: String,
    val remoteCipherId: String?,
    val attachmentId: String,
    // file info
    val source: Source,
    val name: String,
    val encryptionKey: ByteArray?,
) {
    sealed interface Source

    data class UrlSource(
        val url: String,
        val urlIsOneTime: Boolean,
    ) : Source

    data class DirectSource(
        val data: ByteArray,
    ) : Source {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DirectSource

            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DownloadAttachmentRequestData

        if (localCipherId != other.localCipherId) return false
        if (remoteCipherId != other.remoteCipherId) return false
        if (attachmentId != other.attachmentId) return false
        if (source != other.source) return false
        if (!encryptionKey.contentEquals(other.encryptionKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = localCipherId.hashCode()
        result = 31 * result + (remoteCipherId?.hashCode() ?: 0)
        result = 31 * result + attachmentId.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + encryptionKey.contentHashCode()
        return result
    }
}
