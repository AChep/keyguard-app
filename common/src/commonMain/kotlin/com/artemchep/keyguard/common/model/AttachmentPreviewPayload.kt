package com.artemchep.keyguard.common.model

data class AttachmentPreviewPayload(
    val fileName: String,
    val encryptedSize: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AttachmentPreviewPayload

        if (encryptedSize != other.encryptedSize) return false
        if (fileName != other.fileName) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = encryptedSize.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
