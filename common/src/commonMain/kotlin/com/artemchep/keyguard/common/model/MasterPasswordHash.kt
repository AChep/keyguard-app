package com.artemchep.keyguard.common.model

data class MasterPasswordHash(
    val kdf: MasterKdf,
    val byteArray: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (this::class != other?.let { it::class }) return false

        other as MasterPasswordHash

        if (kdf != other.kdf) return false
        if (!byteArray.contentEquals(other.byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = kdf.hashCode()
        result = 31 * result + byteArray.contentHashCode()
        return result
    }
}
