package com.artemchep.keyguard.common.model

data class MasterPasswordSalt(
    val byteArray: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MasterPasswordSalt

        if (!byteArray.contentEquals(other.byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        return byteArray.contentHashCode()
    }
}
