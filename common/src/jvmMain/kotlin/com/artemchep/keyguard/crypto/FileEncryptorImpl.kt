package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.InputStream

class FileEncryptorImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
) : FileEncryptor {
    companion object {
        private const val TAG = "FileEncryptorImpl"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun decode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val type = data[0].let { byte ->
            CipherEncryptor.Type.entries.first { it.byte == byte }
        }

        return when (type) {
            CipherEncryptor.Type.AesCbc256_B64 ->
                decodeAesCbc256_B64(data, key)

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 ->
                decodeAesCbc128_HmacSha256_B64(data, key)

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 ->
                decodeAesCbc256_HmacSha256_B64(data, key)

            else -> throw IllegalArgumentException("Can not decrypt data with a type of '$type'!")
        }
    }

    override fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream {
        val decoder = CipherInputStreamDecoder(key = key)
        return CipherInputStream2(input, decoder)
    }

    private fun decodeAesCbc256_B64(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        check(data.size >= 17) { "Invalid encrypted data" } // 1 + 16 + ctLength
        val iv = data.sliceArray(1..17)
        val ct = data.sliceArray(18 until data.size)
        require(iv.size + ct.size + 1 == data.size)
        TODO()
    }

    private fun decodeAesCbc128_HmacSha256_B64(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        check(data.size >= 49) { "Invalid encrypted data" } // 1 + 16 + 32 + ctLength
        val typeLength = 1
        val ivLength = 16
        val macLength = 32

        @Suppress("UnnecessaryVariable")
        val typeEnd = typeLength
        val ivEnd = typeLength + ivLength
        val macEnd = ivEnd + macLength
        val iv = data.sliceArray(typeEnd until ivEnd)
        val mac = data.sliceArray(ivEnd until macEnd)
        val ct = data.sliceArray(macEnd until data.size)
        require(iv.size + mac.size + ct.size + 1 == data.size)
        return decodeAesCbc256_HmacSha256_B64(
            iv = iv,
            ct = ct,
            encKey = key,
        )
    }

    private fun decodeAesCbc256_HmacSha256_B64(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        check(data.size >= 49) { "Invalid encrypted data" } // 1 + 16 + 32 + ctLength
        val typeLength = 1
        val ivLength = 16
        val macLength = 32

        @Suppress("UnnecessaryVariable")
        val typeEnd = typeLength
        val ivEnd = typeLength + ivLength
        val macEnd = ivEnd + macLength
        val iv = data.sliceArray(typeEnd until ivEnd)
        val mac = data.sliceArray(ivEnd until macEnd)
        val ct = data.sliceArray(macEnd until data.size)
        require(iv.size + mac.size + ct.size + 1 == data.size)
        return decodeAesCbc256_HmacSha256_B64(
            iv = iv,
            ct = ct,
            encKey = key.sliceArray(0 until 32),
        )
    }

    override fun encode(data: ByteArray, key: ByteArray): ByteArray {
        TODO()
    }

    private fun decodeAesCbc256_HmacSha256_B64(
        iv: ByteArray,
        ct: ByteArray,
        encKey: ByteArray,
    ): ByteArray {
        val aes = createAesCbc(iv, encKey, forEncryption = false)
        return cipherData(aes, ct)
    }

    private fun createAesCbc(
        iv: ByteArray,
        key: ByteArray,
        forEncryption: Boolean,
    ) = kotlin.run {
        val aes = PaddedBufferedBlockCipher(
            CBCBlockCipher(
                AESEngine(),
            ),
            PKCS7Padding(),
        )
        val ivAndKey: CipherParameters = ParametersWithIV(KeyParameter(key), iv)
        aes.init(forEncryption, ivAndKey)
        aes
    }

    @Throws(Exception::class)
    private fun cipherData(cipher: BufferedBlockCipher, data: ByteArray): ByteArray {
        val minSize = cipher.getOutputSize(data.size)
        val outBuf = ByteArray(minSize)
        val length1 = cipher.processBytes(data, 0, data.size, outBuf, 0)
        val length2 = cipher.doFinal(outBuf, length1)
        val actualLength = length1 + length2
        val result = ByteArray(actualLength)
        System.arraycopy(outBuf, 0, result, 0, result.size)
        return result
    }
}
