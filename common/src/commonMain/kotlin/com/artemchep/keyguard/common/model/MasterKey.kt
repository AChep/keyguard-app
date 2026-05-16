package com.artemchep.keyguard.common.model

data class MasterKey(
    val version: MasterKdfVersion,
    val byteArray: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (this::class != other?.let { it::class }) return false

        other as MasterKey

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
