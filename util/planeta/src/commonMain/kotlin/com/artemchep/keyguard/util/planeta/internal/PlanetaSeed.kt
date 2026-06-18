package com.artemchep.keyguard.util.planeta.internal

internal fun fingerprintSeed(fingerprint: String): Long {
    var hash = 0x14650FB0739D0383L
    fingerprint.encodeToByteArray().forEach { byte ->
        hash = hash xor (byte.toLong() and 0xffL)
        hash *= 0x100000001B3L
    }
    return if (hash == 0L) 0x5DEECE66DL else hash
}
