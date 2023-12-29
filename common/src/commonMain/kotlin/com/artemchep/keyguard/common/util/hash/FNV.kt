package com.artemchep.keyguard.common.util.hash

object FNV {
    private const val init32 = 0x811c9dc5L
    private const val prime32 = 0x01000193L
    private const val mod32 = 1L shl 32

    fun fnv1_32(data: String): Long {
        var hash = init32
        for (b in data) {
            hash = hash.times(prime32).mod(mod32)
            hash = hash.xor(b.code.toLong())
        }
        return hash
    }

    fun fnv1_32(data: ByteArray): Long {
        var hash = init32
        for (b in data) {
            hash = hash.times(prime32).mod(mod32)
            hash = hash.xor(b.toLong())
        }
        return hash
    }
}
