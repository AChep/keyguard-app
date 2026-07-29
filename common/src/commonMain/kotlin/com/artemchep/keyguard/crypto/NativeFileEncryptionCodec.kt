package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.crypto.FileEncryptionFormat.BUFFER_SIZE
import com.artemchep.keyguard.crypto.FileEncryptionFormat.HEADER_LENGTH
import com.artemchep.keyguard.crypto.FileEncryptionFormat.IV_LENGTH
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeCryptoSession
import com.artemchep.keyguard.util.io.consumeWithErasedBuffer
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import com.artemchep.keyguard.util.io.spool.buildSnapshot
import com.artemchep.keyguard.util.io.spool.copyTo
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.kodein.di.DirectDI
import org.kodein.di.instance

open class NativeFileEncryptionCodec internal constructor(
    private val cryptoGenerator: CryptoGenerator,
    private val stagingSpoolFactory: StagingSpoolFactory =
        DefaultStagingSpoolFactory(),
) : FileEncryptionCodec {
    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
        stagingSpoolFactory = directDI.instance(),
    )

    override fun decrypt(
        input: ByteArray,
        key: ByteArray,
    ): ByteArray = NativeFileCrypto.decode(input, key)

    final override fun decrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ) {
        val headerBytes = input.readByteArray(HEADER_LENGTH)
        try {
            val header = FileEncryptionFormat.parseAuthenticatedHeader(headerBytes, offset = 0)
            try {
                decryptAuthenticatedBody(
                    source = input,
                    output = output,
                    header = header,
                    key = key,
                )
            } finally {
                header.iv.fill(0)
                header.mac.fill(0)
            }
        } finally {
            headerBytes.fill(0)
        }
    }

    private fun decryptAuthenticatedBody(
        source: Source,
        output: Sink,
        header: FileEncryptionFormat.AuthenticatedHeader,
        key: ByteArray,
    ) {
        val keys = NativeFileCrypto.keys(header.type, key)
        try {
            stageAndAuthenticateCiphertext(
                input = source,
                iv = header.iv,
                expectedMac = header.mac,
                macKey = keys.macKey,
            ).use { ciphertext ->
                decryptAuthenticatedCiphertext(
                    ciphertext = ciphertext,
                    output = output,
                    encryptionKey = keys.encKey,
                    iv = header.iv,
                )
            }
        } catch (failure: NativeCryptoException) {
            NativeFileCrypto.rethrowAuthenticationFailure(failure)
        } finally {
            with(NativeFileCrypto) { keys.clear() }
        }
    }

    override fun encrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray = NativeFileCrypto.encode(
        data = data,
        key = key,
        iv = cryptoGenerator.seed(IV_LENGTH),
    )

    override fun encrypt(
        input: Source,
        output: Sink,
        key: ByteArray,
    ): FileEncryptionCodec.EncryptionResult {
        val iv = cryptoGenerator.seed(IV_LENGTH)
        val keys = FileEncryptionFormat.requireAesCbc256HmacSha256Keys(key)
        var mac: ByteArray? = null
        var plainSize = 0L
        try {
            val ciphertext = createFileCiphertextSpool().buildSnapshot { ciphertextSink ->
                NativeCryptoPrimitives.createAesCbcPkcs7HmacSha256Encryptor(
                    encryptionKey = keys.encKey,
                    macKey = keys.macKey,
                    iv = iv,
                ).use { authenticatedCipher ->
                    input.consumeWithErasedBuffer(bufferSize = BUFFER_SIZE) { buffer, length ->
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

            ciphertext.use {
                val finalMac = checkNotNull(mac)
                output.writeByte(CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte)
                output.write(iv)
                output.write(finalMac)
                ciphertext.copyTo(output)

                return FileEncryptionCodec.EncryptionResult(
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
        iv: ByteArray,
        expectedMac: ByteArray,
        macKey: ByteArray,
    ): ByteSnapshot = createFileCiphertextSpool().buildSnapshot { ciphertextSink ->
        val actualMac = NativeCryptoPrimitives.createHmacSha256(macKey).use { hmac ->
            updateHmac(hmac, iv, iv.size)
            input.consumeWithErasedBuffer(bufferSize = BUFFER_SIZE) { buffer, length ->
                updateHmac(hmac, buffer, length)
                ciphertextSink.write(buffer, 0, length)
            }
            hmac.finish()
        }
        try {
            // A mismatch throws before the spool seals, so unauthenticated
            // ciphertext never escapes as a snapshot.
            FileEncryptionFormat.verifyMac(
                expectedMac = expectedMac,
                actualMac = actualMac,
            )
        } finally {
            actualMac.fill(0)
        }
    }

    private fun decryptAuthenticatedCiphertext(
        ciphertext: ByteSnapshot,
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
                ciphertext.copyTo(decryptingSink)
            }

            val finalPlaintext = decryptor.finish()
            try {
                output.write(finalPlaintext)
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

    private fun createFileCiphertextSpool(): ByteStoreWriter = stagingSpoolFactory.create(
        purpose = StagingPurpose.FileCiphertext,
        limits = SpoolLimits(
            memoryBytes = MAX_IN_MEMORY_FILE_CIPHERTEXT_BYTES,
            maximumBytes = MAX_STAGED_FILE_CIPHERTEXT_BYTES,
        ),
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
