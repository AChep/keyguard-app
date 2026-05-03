package com.artemchep.keyguard.common.service.crypto

import java.io.ByteArrayInputStream
import java.io.InputStream
import com.artemchep.keyguard.platform.LocalPath
import kotlinx.io.Source

interface FileEncryptor {
    data class EncodeResult(
        val plainSize: Long,
        val encryptedSize: Long,
    )

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

    fun encode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ): EncodeResult
}
