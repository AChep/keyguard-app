package com.artemchep.keyguard.common.model

data class MasterPasswordHash(
    val version: MasterKdfVersion,
    val byteArray: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MasterPasswordHash

        if (version != other.version) return false
        if (!byteArray.contentEquals(other.byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + byteArray.contentHashCode()
        return result
    }
}
