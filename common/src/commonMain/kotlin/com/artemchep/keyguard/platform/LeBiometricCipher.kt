package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import kotlinx.coroutines.Dispatchers

interface LeBiometricCipher {
    val iv: ByteArray

    fun encode(data: ByteArray): ByteArray
}

fun ByteArray.encode(cipher: IO<LeBiometricCipher>) = cipher
    .effectMap(Dispatchers.Default) { c ->
        c.encode(this)
    }

fun IO<ByteArray>.encode(cipher: IO<LeBiometricCipher>) = this
    .flatMap { bytes ->
        bytes.encode(cipher)
    }
