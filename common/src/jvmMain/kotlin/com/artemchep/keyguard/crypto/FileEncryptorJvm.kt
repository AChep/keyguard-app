package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.crypto.FileEncryptionFormat.BUFFER_SIZE
import com.artemchep.keyguard.crypto.FileEncryptionFormat.IV_LENGTH
import com.artemchep.keyguard.crypto.FileEncryptionFormat.MAC_LENGTH
import com.artemchep.keyguard.crypto.FileEncryptionFormat.TYPE_LENGTH
import com.artemchep.keyguard.crypto.util.createAesCbc
import com.artemchep.keyguard.crypto.util.encode
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toJavaFile
import com.artemchep.keyguard.util.foundation.crypto.createHmacSha256
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.io.Buffer
import kotlinx.io.Source

class FileEncryptorJvm(
    private val cryptoGenerator: CryptoGenerator,
) : FileEncryptor {
    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
    )

    override fun decode(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val type = FileEncryptionFormat.readType(input)

        return when (type) {
            CipherEncryptor.Type.AesCbc256_B64 -> {
                val msg = "The support for AES CBC 256 (enc-type 0) is not longer provided! " +
                        "Please upgrade your vault to migrate to a newer encryption type!"
                throw IllegalArgumentException(msg)
            }

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 ->
                decodeAesCbc128_HmacSha256_B64(input, key)

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 ->
                decodeAesCbc256_HmacSha256_B64(input, key)

            else -> throw IllegalArgumentException("Can not decrypt data with a type of '$type'!")
        }
    }

    fun decode(
        input: InputStream,
        key: ByteArray,
    ): InputStream {
        val decoder = CipherInputStreamDecoder(key = key)
        return CipherInputStream2(input, decoder)
    }

    private fun decodeAesCbc128_HmacSha256_B64(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val frame = FileEncryptionFormat.parseAuthenticatedFrame(data)
        val keys = FileEncryptionFormat.requireAesCbc128HmacSha256Keys(key)
        verifyMac(frame, keys)
        return decryptAesCbc(
            iv = frame.iv,
            cipherText = frame.cipherText,
            encKey = keys.encKey,
        )
    }

    private fun decodeAesCbc256_HmacSha256_B64(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val frame = FileEncryptionFormat.parseAuthenticatedFrame(data)
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        verifyMac(frame, keys)
        return decryptAesCbc(
            iv = frame.iv,
            cipherText = frame.cipherText,
            encKey = keys.encKey,
        )
    }

    override fun encode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        val iv = cryptoGenerator.seed(IV_LENGTH)
        val aes = createAesCbc(iv, keys.encKey, forEncryption = true)
        val cipherText = aes.encode(data)
        val mac = cryptoGenerator.hmacSha256(keys.macKey, iv + cipherText)
        return byteArrayOf(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte) + iv + mac + cipherText
    }

    override fun encode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ): FileEncryptor.EncodeResult {
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        val iv = cryptoGenerator.seed(IV_LENGTH)
        val aes = createAesCbc(iv, keys.encKey, forEncryption = true)
        val hmac = createHmacSha256(keys.macKey).apply {
            update(iv)
        }

        val outputFile = output.toJavaFile()
        outputFile.parentFile?.mkdirs()

        RandomAccessFile(outputFile, "rw").use { file ->
            file.setLength(0L)
            file.writeByte(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte.toInt())
            file.write(iv)
            file.write(ByteArray(MAC_LENGTH))

            val inputBuffer = Buffer()
            val chunkBuffer = ByteArray(BUFFER_SIZE)
            var plainSize = 0L

            input.use { source ->
                while (true) {
                    val read = source.readAtMostTo(inputBuffer, BUFFER_SIZE.toLong())
                    if (read == -1L) {
                        break
                    }
                    plainSize += read

                    while (!inputBuffer.exhausted()) {
                        val chunkLength = minOf(inputBuffer.size.toInt(), chunkBuffer.size)
                        inputBuffer.readAtMostTo(chunkBuffer, 0, chunkLength)

                        val outputBuffer = ByteArray(aes.getUpdateOutputSize(chunkLength))
                        val written = aes.processBytes(
                            chunkBuffer,
                            0,
                            chunkLength,
                            outputBuffer,
                            0,
                        )
                        if (written > 0) {
                            file.write(outputBuffer, 0, written)
                            hmac.update(outputBuffer, 0, written)
                        }
                    }
                }
            }

            val finalBuffer = ByteArray(aes.getOutputSize(0))
            val finalWritten = aes.doFinal(finalBuffer, 0)
            if (finalWritten > 0) {
                file.write(finalBuffer, 0, finalWritten)
                hmac.update(finalBuffer, 0, finalWritten)
            }

            val mac = hmac.doFinal()
            file.seek((TYPE_LENGTH + IV_LENGTH).toLong())
            file.write(mac)

            return FileEncryptor.EncodeResult(
                plainSize = plainSize,
                encryptedSize = file.length(),
            )
        }
    }

    private fun verifyMac(
        frame: FileEncryptionFormat.AuthenticatedFrame,
        keys: FileEncryptionFormat.EncryptionKeys,
    ) {
        val actualMac = cryptoGenerator.hmacSha256(keys.macKey, frame.iv + frame.cipherText)
        FileEncryptionFormat.verifyMac(
            expectedMac = frame.mac,
            actualMac = actualMac,
        )
    }

    private fun decryptAesCbc(
        iv: ByteArray,
        cipherText: ByteArray,
        encKey: ByteArray,
    ): ByteArray {
        val aes = createAesCbc(iv, encKey, forEncryption = false)
        return aes.encode(cipherText)
    }
}
