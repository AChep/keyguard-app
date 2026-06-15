package com.artemchep.keyguard.platform

actual class LeCipher(
    val iv: ByteArray = ByteArray(0),
)

actual val LeCipher.leIv: ByteArray get() = iv
