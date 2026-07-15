package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.crypto.FileEncryptionFormat.BUFFER_SIZE
import com.artemchep.keyguard.crypto.FileEncryptionFormat.HEADER_LENGTH
import com.artemchep.keyguard.crypto.FileEncryptionFormat.IV_LENGTH
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.foundation.io.AdaptiveSpool
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.kodein.di.DirectDI
import org.kodein.di.instance

open class NativeFileEncryptor(
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
    ): ByteArray = NativeFileCrypto.decode(input, key)

    override fun decode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ) = input.use { source ->
        val headerBytes = source.readByteArray(HEADER_LENGTH)
        try {
            val header = FileEncryptionFormat.parseAuthenticatedHeader(headerBytes, offset = 0)
            try {
                val keys = NativeFileCrypto.keys(header.type, key)
                try {
                    createFileCiphertextSpool().use { ciphertext ->
                        stageAndAuthenticateCiphertext(
                            input = source,
                            ciphertext = ciphertext,
                            iv = header.iv,
                            expectedMac = header.mac,
                            macKey = keys.macKey,
                        )

                        createPrivateAtomicOutput(output).use { stagedOutput ->
                            decryptAuthenticatedCiphertext(
                                ciphertext = ciphertext,
                                output = stagedOutput.sink(),
                                encryptionKey = keys.encKey,
                                iv = header.iv,
                            )
                            stagedOutput.commit()
                        }
                    }
                } catch (failure: NativeCryptoException) {
                    NativeFileCrypto.rethrowAuthenticationFailure(failure)
                } finally {
                    with(NativeFileCrypto) { keys.clear() }
                }
            } finally {
                header.iv.fill(0)
                header.mac.fill(0)
            }
        } finally {
            headerBytes.fill(0)
        }
    }

    override fun encode(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = NativeFileCrypto.encode(
        data = data,
        key = key,
        iv = cryptoGenerator.seed(IV_LENGTH),
    )

    override fun encode(
        input: Source,
        output: LocalPath,
        key: ByteArray,
    ): FileEncryptor.EncodeResult {
        val iv = cryptoGenerator.seed(IV_LENGTH)
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        var mac: ByteArray? = null
        var plainSize = 0L
        try {
            createFileCiphertextSpool().use { ciphertext ->
                NativeCryptoPrimitives.createAesCbcPkcs7HmacSha256Encryptor(
                    encryptionKey = keys.encKey,
                    macKey = keys.macKey,
                    iv = iv,
                ).use { authenticatedCipher ->
                    ciphertext.sink().use { ciphertextSink ->
                        input.use { source ->
                            source.consumeWithErasedBuffer(bufferSize = BUFFER_SIZE) { buffer, length ->
                                if (length.toLong() > MAX_STAGED_FILE_PLAINTEXT_BYTES - plainSize) {
                                    throw fileSizeLimitExceeded()
                                }
                                plainSize += length
                                val encrypted = authenticatedCipher.update(
                                    data = buffer,
                                    length = length,
                                )
                                try {
                                    ciphertextSink.write(encrypted)
                                } finally {
                                    encrypted.fill(0)
                                }
                            }
                        }

                        val result = authenticatedCipher.finish()
                        try {
                            ciphertextSink.write(result.ciphertext)
                            mac = result.mac.copyOf()
                        } finally {
                            result.ciphertext.fill(0)
                            result.mac.fill(0)
                        }
                    }
                }
                ciphertext.seal()

                val finalMac = checkNotNull(mac)
                createPrivateAtomicOutput(output).use { stagedOutput ->
                    val sink = stagedOutput.sink()
                    sink.writeByte(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte)
                    sink.write(iv)
                    sink.write(finalMac)
                    ciphertext.replayTo(sink)
                    sink.flush()
                    stagedOutput.commit()
                }

                return FileEncryptor.EncodeResult(
                    plainSize = plainSize,
                    encryptedSize = HEADER_LENGTH + ciphertext.size,
                )
            }
        } finally {
            with(NativeFileCrypto) { keys.clear() }
            iv.fill(0)
            mac?.fill(0)
        }
    }

    private fun stageAndAuthenticateCiphertext(
        input: Source,
        ciphertext: AdaptiveSpool,
        iv: ByteArray,
        expectedMac: ByteArray,
        macKey: ByteArray,
    ) {
        val actualMac = NativeCryptoPrimitives.createHmacSha256(macKey).use { hmac ->
            updateHmac(hmac, iv, iv.size)
            ciphertext.sink().use { ciphertextSink ->
                input.consumeWithErasedBuffer(bufferSize = BUFFER_SIZE) { buffer, length ->
                    updateHmac(hmac, buffer, length)
                    ciphertextSink.write(buffer, 0, length)
                }
            }
            ciphertext.seal()
            hmac.finish()
        }
        try {
            FileEncryptionFormat.verifyMac(
                expectedMac = expectedMac,
                actualMac = actualMac,
            )
        } finally {
            actualMac.fill(0)
        }
    }

    private fun decryptAuthenticatedCiphertext(
        ciphertext: AdaptiveSpool,
        output: Sink,
        encryptionKey: ByteArray,
        iv: ByteArray,
    ) {
        NativeCryptoPrimitives.createAesCbcPkcs7Decryptor(
            key = encryptionKey,
            iv = iv,
        ).use { decryptor ->
            NativeSessionTransformRawSink(
                session = decryptor,
                output = output,
            ).buffered().use { decryptingSink ->
                ciphertext.replayTo(decryptingSink)
            }

            val finalPlaintext = decryptor.finish()
            try {
                output.write(finalPlaintext)
                output.flush()
            } finally {
                finalPlaintext.fill(0)
            }
        }
    }

    private fun updateHmac(
        hmac: NativeCryptoSession,
        data: ByteArray,
        length: Int,
    ) {
        NativeFileCrypto.updateChunked(hmac, data, length = length) { output ->
            check(output.isEmpty()) { "Native HMAC update produced unexpected output" }
        }
    }

    private fun createFileCiphertextSpool(): AdaptiveSpool = AdaptiveSpool(
        memoryLimitBytes = MAX_IN_MEMORY_FILE_CIPHERTEXT_BYTES,
        maximumBytes = MAX_STAGED_FILE_CIPHERTEXT_BYTES,
        spillFactory = {
            PrivateTemporarySpillStorage(createPrivateTemporaryStorage())
        },
        limitExceeded = { fileSizeLimitExceeded() },
    )

    private fun fileSizeLimitExceeded(): IOException = IOException(
        "File exceeds the supported staging limit of $MAX_STAGED_FILE_PLAINTEXT_BYTES bytes",
    )

    private class NativeSessionTransformRawSink(
        private val session: NativeCryptoSession,
        private val output: Sink,
    ) : RawSink {
        private val buffer = ByteArray(BUFFER_SIZE)
        private var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            check(!closed) { "Native transform sink is closed" }
            require(byteCount >= 0L && byteCount <= source.size) {
                "Invalid native transform write size"
            }

            var remaining = byteCount
            while (remaining > 0L) {
                val requested = minOf(remaining, buffer.size.toLong()).toInt()
                val read = source.readAtMostTo(
                    buffer,
                    startIndex = 0,
                    endIndex = requested,
                )
                check(read > 0) { "Native transform source ended early" }
                try {
                    NativeFileCrypto.updateChunked(
                        session = session,
                        data = buffer,
                        length = read,
                    ) { transformed ->
                        output.write(transformed)
                    }
                } finally {
                    buffer.fill(0, fromIndex = 0, toIndex = read)
                }
                remaining -= read
            }
        }

        override fun flush() {
            check(!closed) { "Native transform sink is closed" }
            output.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            buffer.fill(0)
        }
    }
}

internal const val MAX_IN_MEMORY_FILE_CIPHERTEXT_BYTES: Long = 8L * 1024L * 1024L
internal const val MAX_STAGED_FILE_PLAINTEXT_BYTES: Long = 16L * 1024L * 1024L * 1024L
private const val FILE_CIPHER_BLOCK_BYTES = 16L
internal const val MAX_STAGED_FILE_CIPHERTEXT_BYTES: Long =
    MAX_STAGED_FILE_PLAINTEXT_BYTES + FILE_CIPHER_BLOCK_BYTES
