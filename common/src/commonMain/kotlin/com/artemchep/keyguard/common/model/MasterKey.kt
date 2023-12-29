package com.artemchep.keyguard.common.model

data class MasterKey(
    val byteArray: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MasterKey

        if (!byteArray.contentEquals(other.byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        return byteArray.contentHashCode()
    }
}
