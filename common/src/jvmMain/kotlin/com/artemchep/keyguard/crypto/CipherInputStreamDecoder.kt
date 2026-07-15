package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.crypto.FileEncryptionFormat.HEADER_LENGTH
import com.artemchep.keyguard.nativecrypto.NativeAesCbcPkcs7HmacSha256DecryptSession
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives

internal class CipherInputStreamDecoder(
    private val key: ByteArray,
) : CipherInputStream2.Decoder {
    private val headerBuffer = ByteArray(HEADER_LENGTH)
    private var headerLength = 0
    private var provisionalDecryptor: NativeAesCbcPkcs7HmacSha256DecryptSession? = null

    override fun processBytes(
        `in`: ByteArray,
        inOff: Int,
        len: Int,
        out: ByteArray,
        outOff: Int,
    ): Int {
        val consumedLength = initIfReady(`in`, inOff, len)
        val ciphertextOffset = inOff + consumedLength
        val ciphertextLength = len - consumedLength
        if (ciphertextLength == 0) return 0

        var written = 0
        NativeFileCrypto.updateProvisionalChunked(
            session = checkNotNull(provisionalDecryptor),
            data = `in`,
            offset = ciphertextOffset,
            length = ciphertextLength,
        ) { provisionalPlaintext ->
            provisionalPlaintext.copyInto(out, destinationOffset = outOff + written)
            written += provisionalPlaintext.size
        }
        return written
    }

    override fun doFinal(
        out: ByteArray,
        outOff: Int,
    ): Int {
        var primaryFailure: Throwable? = null
        return try {
            val provisionalDecryptor = checkNotNull(provisionalDecryptor) { "Invalid encrypted data" }
            check(headerLength == HEADER_LENGTH) { "Invalid encrypted data" }

            val finalPlaintext = try {
                provisionalDecryptor.authenticateAndFinish()
            } catch (failure: NativeCryptoException) {
                NativeFileCrypto.rethrowAuthenticationFailure(failure)
            }
            try {
                finalPlaintext.copyInto(out, destinationOffset = outOff)
                finalPlaintext.size
            } finally {
                finalPlaintext.fill(0)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val closeFailure = closeSession(primaryFailure)
            provisionalDecryptor = null
            headerBuffer.fill(0)
            if (primaryFailure == null && closeFailure != null) {
                throw closeFailure
            }
        }
    }

    private fun closeSession(primaryFailure: Throwable?): Throwable? = try {
        provisionalDecryptor?.close()
        null
    } catch (closeFailure: Throwable) {
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(closeFailure)
            null
        } else {
            closeFailure
        }
    }

    private fun initIfReady(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (provisionalDecryptor != null) return 0

        val consumedLength = minOf(HEADER_LENGTH - headerLength, length)
        buffer.copyInto(
            destination = headerBuffer,
            destinationOffset = headerLength,
            startIndex = offset,
            endIndex = offset + consumedLength,
        )
        headerLength += consumedLength
        if (headerLength < HEADER_LENGTH) return consumedLength

        val header = FileEncryptionFormat.parseAuthenticatedHeader(headerBuffer, offset = 0)
        val keys = NativeFileCrypto.keys(header.type, key)
        try {
            provisionalDecryptor = NativeCryptoPrimitives.createAesCbcPkcs7HmacSha256Decryptor(
                encryptionKey = keys.encKey,
                macKey = keys.macKey,
                iv = header.iv,
                expectedMac = header.mac,
            )
        } finally {
            with(NativeFileCrypto) { keys.clear() }
            header.iv.fill(0)
            header.mac.fill(0)
        }
        return consumedLength
    }

    override fun getOutputSize(length: Int): Int =
        if (provisionalDecryptor == null) length else length + AES_BLOCK_SIZE

    override fun getUpdateOutputSize(length: Int): Int =
        if (provisionalDecryptor == null) length else length + AES_BLOCK_SIZE

    private companion object {
        const val AES_BLOCK_SIZE = 16
    }
}
