package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.crypto.FileEncryptionFormat.HEADER_LENGTH
import com.artemchep.keyguard.crypto.util.createAesCbc
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.InvalidCipherTextException
import javax.crypto.Mac

class CipherInputStreamDecoder(
    private val key: ByteArray,
) : CipherInputStream2.Decoder {
    private val headerBuffer = ByteArray(HEADER_LENGTH)
    private var headerLength = 0
    private var bufferedBlockCipher: BufferedBlockCipher? = null
    private var hmac: Mac? = null
    private var expectedMac: ByteArray? = null

    override fun processBytes(
        `in`: ByteArray,
        inOff: Int,
        len: Int,
        out: ByteArray,
        outOff: Int,
    ): Int {
        val consumedLength = initIfReady(
            buffer = `in`,
            offset = inOff,
            length = len,
        )

        val newOffset = inOff + consumedLength
        val newLength = len - consumedLength
        if (newLength == 0) {
            return 0
        }

        hmac!!.update(`in`, newOffset, newLength)
        return bufferedBlockCipher!!.processBytes(
            `in`,
            newOffset,
            newLength,
            out,
            outOff,
        )
    }

    override fun doFinal(out: ByteArray, outOff: Int): Int {
        check(headerLength == HEADER_LENGTH && bufferedBlockCipher != null) {
            "Invalid encrypted data"
        }
        try {
            FileEncryptionFormat.verifyMac(
                expectedMac = expectedMac!!,
                actualMac = hmac!!.doFinal(),
            )
        } catch (e: IllegalStateException) {
            throw InvalidCipherTextException(e.message, e)
        }
        return bufferedBlockCipher!!.doFinal(out, outOff)
    }

    private fun initIfReady(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (bufferedBlockCipher != null) {
            return 0
        }

        val headerRemaining = HEADER_LENGTH - headerLength
        val consumedLength = minOf(headerRemaining, length)
        buffer.copyInto(
            destination = headerBuffer,
            destinationOffset = headerLength,
            startIndex = offset,
            endIndex = offset + consumedLength,
        )
        headerLength += consumedLength
        if (headerLength < HEADER_LENGTH) {
            return consumedLength
        }

        val header = FileEncryptionFormat.parseAuthenticatedHeader(
            buffer = headerBuffer,
            offset = 0,
        )
        val keys = when (header.type) {
            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 ->
                FileEncryptionFormat.requireAesCbc128HmacSha256Keys(key)

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 ->
                FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)

            else -> throw IllegalArgumentException("Can not decrypt data with a type of '${header.type}'!")
        }
        expectedMac = header.mac
        hmac = FileEncryptionFormat.createHmac(keys.macKey).apply {
            update(header.iv)
        }
        bufferedBlockCipher = createAesCbc(
            iv = header.iv,
            key = keys.encKey,
            forEncryption = false,
        )
        return consumedLength
    }

    override fun getOutputSize(length: Int) =
        bufferedBlockCipher?.getOutputSize(length) ?: length

    override fun getUpdateOutputSize(length: Int) =
        bufferedBlockCipher?.getUpdateOutputSize(length) ?: length
}
