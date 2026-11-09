package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.crypto.FileEncryptionFormat
import com.artemchep.keyguard.crypto.NativeFileCrypto
import com.artemchep.keyguard.feature.p2psync.session.MessagePadding
import com.artemchep.keyguard.feature.p2psync.transport.Decompressed

/**
 * Seals the items the devices leave on the relay, see [MailboxKeys] and [GroupObjects]:
 * `pad(gzip(plaintext))`, see [MessagePadding], encrypted with AES-256-CBC and HMAC-SHA256
 * in the binary layout of encrypted files, see [NativeFileCrypto], with a random IV. [open]
 * checks the MAC before it decompresses anything.
 */
internal object MailboxSeal {
    /** The bytes of a key that seals an item: 32 for AES-256, 32 for HMAC-SHA256. */
    const val KEY_SIZE: Int = 64

    /** Seals the [plaintext] of an item with the [key]. */
    fun seal(
        plaintext: ByteArray,
        key: ByteArray,
        cryptoGenerator: CryptoGenerator,
    ): ByteArray {
        val padded = MessagePadding.pad(GzipCompressor.compress(plaintext))
        val iv = cryptoGenerator.seed(length = FileEncryptionFormat.IV_LENGTH)
        return NativeFileCrypto.encode(padded, key, iv)
    }

    /**
     * Opens the [sealed] item with the [key], to at most [maxSize] bytes. The MAC is checked
     * before anything is decompressed, so an item that is [SealOpening.TooLarge] is the one
     * a device sealed.
     */
    fun open(
        sealed: ByteArray,
        key: ByteArray,
        maxSize: Long,
    ): SealOpening {
        if (sealed.firstOrNull() != CipherEncryptor.Type.AesCbc256_HmacSha256_B64.byte) {
            return if (sealed.isEmpty()) SealOpening.Corrupt else SealOpening.Unsupported
        }
        val limit = maxSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val decompressed = decryptOrNull(sealed, key)
            ?.let(MessagePadding::unpad)
            ?.let { GzipCompressor.decompress(it, limit) }
        return when (decompressed) {
            is Decompressed.Bytes -> SealOpening.Opened(decompressed.bytes)
            Decompressed.TooLarge -> SealOpening.TooLarge
            // The MAC does not match, or the padding or the gzip stream is broken.
            Decompressed.Invalid, null -> SealOpening.Corrupt
        }
    }

    private fun decryptOrNull(sealed: ByteArray, key: ByteArray): ByteArray? = try {
        NativeFileCrypto.decode(sealed, key)
    } catch (expected: IllegalStateException) {
        // The MAC does not match.
        null
    } catch (expected: IllegalArgumentException) {
        // Not an encrypted file, or the native crypto refused it.
        null
    }
}

/** What [MailboxSeal.open] made of a sealed item. */
sealed interface SealOpening {
    /** The item opened to its [plaintext]. */
    class Opened(val plaintext: ByteArray) : SealOpening

    /** The item is sealed in a format this build does not know, such as one of a newer build. */
    data object Unsupported : SealOpening

    /** The item is the one a device sealed, but it opens to more than this build takes. */
    data object TooLarge : SealOpening

    /** The item was not sealed with the key, was changed since, or is malformed. */
    data object Corrupt : SealOpening
}
