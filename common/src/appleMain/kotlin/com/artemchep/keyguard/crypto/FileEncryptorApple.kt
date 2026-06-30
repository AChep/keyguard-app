package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.crypto.FileEncryptionFormat.BUFFER_SIZE
import com.artemchep.keyguard.crypto.FileEncryptionFormat.IV_LENGTH
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toKotlinxIoPath
import com.artemchep.keyguard.util.foundation.crypto.createHmacSha256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.kodein.di.DirectDI
import org.kodein.di.instance
import platform.CoreCrypto.CCCryptorCreate
import platform.CoreCrypto.CCCryptorFinal
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess

@OptIn(ExperimentalForeignApi::class)
class FileEncryptorApple(
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

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 -> decodeAesCbcHmacSha256(
                data = input,
                keys = FileEncryptionFormat.requireAesCbc128HmacSha256Keys(key),
            )

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 -> decodeAesCbcHmacSha256(
                data = input,
                keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key),
            )

            else -> throw IllegalArgumentException("Can not decrypt data with a type of '$type'!")
        }
    }

    override fun decode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ) {
        val headerBytes = input.readByteArray(FileEncryptionFormat.HEADER_LENGTH)
        val header = FileEncryptionFormat.parseAuthenticatedHeader(
            buffer = headerBytes,
            offset = 0,
        )
        val keys = when (header.type) {
            CipherEncryptor.Type.AesCbc256_B64 -> {
                val msg = "The support for AES CBC 256 (enc-type 0) is not longer provided! " +
                        "Please upgrade your vault to migrate to a newer encryption type!"
                throw IllegalArgumentException(msg)
            }

            CipherEncryptor.Type.AesCbc128_HmacSha256_B64 ->
                FileEncryptionFormat.requireAesCbc128HmacSha256Keys(key)

            CipherEncryptor.Type.AesCbc256_HmacSha256_B64 ->
                FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)

            else -> throw IllegalArgumentException("Can not decrypt data with a type of '${header.type}'!")
        }
        decodeAesCbcHmacSha256(
            input = input,
            output = output,
            header = header,
            keys = keys,
        )
    }

    private fun decodeAesCbcHmacSha256(
        data: ByteArray,
        keys: FileEncryptionFormat.EncryptionKeys,
    ): ByteArray {
        val frame = FileEncryptionFormat.parseAuthenticatedFrame(data)
        val actualMac = cryptoGenerator.hmacSha256(keys.macKey, frame.iv + frame.cipherText)
        FileEncryptionFormat.verifyMac(
            expectedMac = frame.mac,
            actualMac = actualMac,
        )
        return aesCbcPkcs7(
            data = frame.cipherText,
            iv = frame.iv,
            key = keys.encKey,
            operation = kCCDecrypt,
        )
    }

    private fun decodeAesCbcHmacSha256(
        input: Source,
        output: LocalPath,
        header: FileEncryptionFormat.AuthenticatedHeader,
        keys: FileEncryptionFormat.EncryptionKeys,
    ) {
        val outputPath = output.toKotlinxIoPath()
        outputPath.parent?.let(SystemFileSystem::createDirectories)

        memScoped {
            val cryptorRef = alloc<CCCryptorRefVar>()
            val createStatus = keys.encKey.usePinned { keyPinned ->
                header.iv.usePinned { ivPinned ->
                    CCCryptorCreate(
                        op = kCCDecrypt,
                        alg = kCCAlgorithmAES,
                        options = kCCOptionPKCS7Padding,
                        key = keyPinned.addressOf(0),
                        keyLength = keys.encKey.size.convert(),
                        iv = ivPinned.addressOf(0),
                        cryptorRef = cryptorRef.ptr,
                    )
                }
            }
            check(createStatus == kCCSuccess) {
                "AES-CBC operation failed with status $createStatus."
            }

            val cryptor = checkNotNull(cryptorRef.value) {
                "AES-CBC operation did not create a cryptor."
            }
            try {
                createHmacSha256(keys.macKey).use { hmac ->
                    hmac.update(header.iv)

                    SystemFileSystem.sink(outputPath)
                        .buffered()
                        .use { sink ->
                            val inputBuffer = ByteArray(BUFFER_SIZE)
                            val outputBuffer = ByteArray(BUFFER_SIZE + kCCBlockSizeAES128.toInt())
                            val outputMoved = alloc<ULongVar>()
                            while (true) {
                                val read = input.readAtMostTo(inputBuffer)
                                if (read == -1) {
                                    break
                                }
                                if (read == 0) {
                                    continue
                                }

                                hmac.update(inputBuffer, 0, read)

                                val updateStatus = inputBuffer.usePinned { inputPinned ->
                                    outputBuffer.usePinned { outputPinned ->
                                        CCCryptorUpdate(
                                            cryptorRef = cryptor,
                                            dataIn = inputPinned.addressOf(0),
                                            dataInLength = read.convert(),
                                            dataOut = outputPinned.addressOf(0),
                                            dataOutAvailable = outputBuffer.size.convert(),
                                            dataOutMoved = outputMoved.ptr,
                                        )
                                    }
                                }
                                check(updateStatus == kCCSuccess) {
                                    "AES-CBC operation failed with status $updateStatus."
                                }

                                val moved = outputMoved.value.toInt()
                                if (moved > 0) {
                                    sink.write(outputBuffer, 0, moved)
                                }
                            }

                            FileEncryptionFormat.verifyMac(
                                expectedMac = header.mac,
                                actualMac = hmac.doFinal(),
                            )

                            val finalBuffer = ByteArray(kCCBlockSizeAES128.toInt())
                            val finalMoved = alloc<ULongVar>()
                            val finalStatus = finalBuffer.usePinned { outputPinned ->
                                CCCryptorFinal(
                                    cryptorRef = cryptor,
                                    dataOut = outputPinned.addressOf(0),
                                    dataOutAvailable = finalBuffer.size.convert(),
                                    dataOutMoved = finalMoved.ptr,
                                )
                            }
                            check(finalStatus == kCCSuccess) {
                                "AES-CBC operation failed with status $finalStatus."
                            }

                            val moved = finalMoved.value.toInt()
                            if (moved > 0) {
                                sink.write(finalBuffer, 0, moved)
                            }
                            sink.flush()
                        }
                }
            } finally {
                CCCryptorRelease(cryptor)
            }
        }
    }

    override fun encode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        val iv = cryptoGenerator.seed(IV_LENGTH)
        val cipherText = aesCbcPkcs7(
            data = data,
            iv = iv,
            key = keys.encKey,
            operation = kCCEncrypt,
        )
        val mac = cryptoGenerator.hmacSha256(keys.macKey, iv + cipherText)
        return byteArrayOf(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte) + iv + mac + cipherText
    }

    override fun encode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ): FileEncryptor.EncodeResult =
        throw UnsupportedOperationException("Streaming file encryption is not supported on this platform.")
}
