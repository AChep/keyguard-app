package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

class CipherInputStreamDecoder(
    private val key: ByteArray,
) : CipherInputStream2.Decoder {
    private var bufferedBlockCipher: BufferedBlockCipher? = null

    override fun processBytes(
        `in`: ByteArray,
        inOff: Int,
        len: Int,
        out: ByteArray,
        outOff: Int,
    ): Int {
        val consumedLength = ensureInitialized(
            buffer = `in`,
            offset = inOff,
            length = len,
        )

        val newOffset = inOff + consumedLength
        val newLength = len - consumedLength
        if (newLength == 0) {
            return 0
        }

        return bufferedBlockCipher!!.processBytes(
            `in`,
            newOffset,
            newLength,
            out,
            outOff,
        )
    }

    override fun doFinal(out: ByteArray, outOff: Int): Int {
        return bufferedBlockCipher!!.doFinal(out, outOff)
    }

    private fun ensureInitialized(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (bufferedBlockCipher != null) {
            return 0
        }

        val type = buffer[offset].let { byte ->
            CipherEncryptor.Type.values()
                .first { it.byte == byte }
        }
        return when (type) {
            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 ->
                initAesCbc128_HmacSha256_B64(
                    buffer = buffer,
                    offset = offset,
                    length = length,
                )

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 ->
                initAesCbc256_HmacSha256_B64(
                    buffer = buffer,
                    offset = offset,
                    length = length,
                )

            else -> throw IllegalArgumentException("Can not decrypt data with a type of '$type'!")
        }
    }

    private fun initAesCbc128_HmacSha256_B64(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(length >= 49) { "Invalid encrypted data" } // 1 + 16 + 32 + ctLength
        val typeLength = 1
        val ivLength = 16
        val macLength = 32

        val typeEnd = typeLength + offset
        val ivEnd = typeEnd + ivLength
        val macEnd = ivEnd + macLength
        val iv = buffer.sliceArray(typeEnd until ivEnd)
        val mac = buffer.sliceArray(ivEnd until macEnd)
        initAesCbc(
            iv = iv,
            forEncryption = false,
        )
        // TODO: We need to compute MAC while decrypting the data,
        //  allowing us to check if the output file is valid.
        return typeLength + ivLength + macLength
    }

    private fun initAesCbc256_HmacSha256_B64(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(length >= 49) { "Invalid encrypted data" } // 1 + 16 + 32 + ctLength
        val typeLength = 1
        val ivLength = 16
        val macLength = 32

        val typeEnd = typeLength + offset
        val ivEnd = typeEnd + ivLength
        val macEnd = ivEnd + macLength
        val iv = buffer.sliceArray(typeEnd until ivEnd)
        val mac = buffer.sliceArray(ivEnd until macEnd)
        initAesCbc(
            iv = iv,
            forEncryption = false,
        )
        // TODO: We need to compute MAC while decrypting the data,
        //  allowing us to check if the output file is valid.
        return typeLength + ivLength + macLength
    }

    private fun initAesCbc(
        iv: ByteArray,
        forEncryption: Boolean,
    ) {
        val aes = PaddedBufferedBlockCipher(
            CBCBlockCipher(
                AESEngine(),
            ),
            PKCS7Padding(),
        )
        val ivAndKey: CipherParameters =
            ParametersWithIV(KeyParameter(key.sliceArray(0 until 32)), iv)
        aes.init(forEncryption, ivAndKey)
        bufferedBlockCipher = aes
    }

    override fun getOutputSize(length: Int) =
        bufferedBlockCipher?.getOutputSize(length) ?: length

    override fun getUpdateOutputSize(length: Int) =
        bufferedBlockCipher?.getUpdateOutputSize(length) ?: length
}
