package com.artemchep.keyguard.common.service.crypto

import java.io.ByteArrayInputStream
import java.io.InputStream

interface FileEncryptor {
    fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray

    fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream {
        val data = input.readBytes()
        val out = decode(data, key)
        return ByteArrayInputStream(out)
    }

    fun encode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray
}
