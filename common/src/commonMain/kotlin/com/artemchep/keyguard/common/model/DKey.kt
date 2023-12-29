package com.artemchep.keyguard.common.model

data class DKey(
    val byteArray: ByteArray,
) {
    //    /**
//     * Lazily encoded byte array into a
//     * form of base 64 symbols.
//     */
//    val base64 by lazy {
//        Base64.encode(byteArray).let(::String)
//    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DKey

        if (!byteArray.contentEquals(other.byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        return byteArray.contentHashCode()
    }
}
