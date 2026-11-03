package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import com.artemchep.keyguard.util.io.spool.requireSnapshotReadRange
import kotlinx.io.IOException

/**
 * Encrypts provisional plaintext in independently authenticated chunks, so a
 * reader can decrypt any offset without replaying the spill from byte zero.
 *
 * Every [CHUNK_BYTES] of plaintext becomes one AES-256-CBC ciphertext followed
 * by its HMAC-SHA256 over `iv || ciphertext`, stored at a fixed stride. The IV
 * of chunk `i` is `AES(ivKey, i)`: unique and unpredictable, and never stored,
 * so a frame moved to another index fails authentication. The keys and the
 * plaintext size never leave memory, which also detects truncated storage.
 *
 * A reader verifies each chunk before exposing any of its bytes. Unlike
 * [EncryptedTemporarySpillStorage], a sequential read can return verified
 * earlier chunks before it reaches a corrupted later one.
 */
internal class EncryptedChunkedSpillStorage private constructor(
    storage: PrivateTemporaryStorage,
    private val keys: ChunkKeys,
    checkCancellation: () -> Unit,
) : EncryptingSpillWriter<RandomAccessByteSnapshot>(storage, checkCancellation) {
    private val chunk = ByteArray(CHUNK_BYTES)
    private var chunkLength = 0
    private var chunkIndex = 0L
    private var plaintextBytes = 0L

    override fun absorb(bytes: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val count = minOf(length - offset, CHUNK_BYTES - chunkLength)
            bytes.copyInto(chunk, chunkLength, offset, offset + count)
            chunkLength += count
            offset += count
            if (chunkLength == CHUNK_BYTES) writeChunk()
        }
    }

    override fun finishSeal(): RandomAccessByteSnapshot {
        if (chunkLength > 0) writeChunk()
        ciphertextSink.close()
        storage.sealForReading()
        return EncryptedChunkedByteSnapshot(
            storage = storage,
            keys = keys,
            size = plaintextBytes,
        )
    }

    override fun closeEncryptor() = chunk.fill(0)

    override fun releaseSecrets() = keys.clear()

    private fun writeChunk() {
        checkCancellation()
        val plaintext = if (chunkLength == chunk.size) chunk else chunk.copyOf(chunkLength)
        val iv = keys.iv(chunkIndex)
        try {
            val result = NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Encrypt(
                encryptionKey = keys.encKey,
                macKey = keys.macKey,
                iv = iv,
                plaintext = plaintext,
            )
            try {
                checkCancellation()
                ciphertextSink.write(result.ciphertext)
                ciphertextSink.write(result.mac)
            } finally {
                result.ciphertext.fill(0)
                result.mac.fill(0)
            }
        } finally {
            iv.fill(0)
            plaintext.fill(0)
            chunk.fill(0, 0, chunkLength)
        }
        plaintextBytes += chunkLength
        chunkIndex += 1
        chunkLength = 0
    }

    companion object {
        /** Takes ownership of [storage], including when initialization fails. */
        fun create(
            storage: PrivateTemporaryStorage,
            checkCancellation: () -> Unit = {},
            randomBytes: (Int) -> ByteArray = NativeCryptoPrimitives::randomBytes,
        ): EncryptedChunkedSpillStorage = createOwning(storage, checkCancellation) { wipeOnFailure ->
            val keys = ChunkKeys.generate(randomBytes)
            wipeOnFailure += keys::clear
            EncryptedChunkedSpillStorage(
                storage = storage,
                keys = keys,
                checkCancellation = checkCancellation,
            )
        }

        const val CHUNK_BYTES = 64 * 1024

        /** Frames of full chunks; only the final frame may be shorter. */
        internal val FRAME_STRIDE = frameLength(CHUNK_BYTES)

        internal fun frameLength(plaintextLength: Int): Int =
            (plaintextLength / AES_BLOCK_BYTES + 1) * AES_BLOCK_BYTES + FileEncryptionFormat.MAC_LENGTH
    }
}

private const val AES_BLOCK_BYTES = 16

/** Encryption and authentication keys, plus the key that derives each chunk's IV. */
private class ChunkKeys(
    private val keys: FileEncryptionFormat.EncryptionKeys,
    private val ivKey: ByteArray,
) {
    val encKey: ByteArray get() = keys.encKey
    val macKey: ByteArray get() = keys.macKey

    /** NIST SP 800-38A, Appendix C: encrypting a unique counter yields an unpredictable CBC IV. */
    fun iv(index: Long): ByteArray {
        val block = ByteArray(AES_BLOCK_BYTES)
        for (i in 0 until Long.SIZE_BYTES) {
            block[AES_BLOCK_BYTES - 1 - i] = (index ushr (Byte.SIZE_BITS * i)).toByte()
        }
        return NativeCryptoPrimitives.aesEcbNoPaddingEncrypt(ivKey, block)
    }

    fun clear() {
        with(NativeFileCrypto) { keys.clear() }
        ivKey.fill(0)
    }

    companion object {
        private const val ENCRYPTION_KEY_BYTES = 64
        private const val IV_KEY_BYTES = 32
        private const val KEY_MATERIAL_BYTES = ENCRYPTION_KEY_BYTES + IV_KEY_BYTES

        /** Splits one random draw, which is zeroed afterwards. */
        fun generate(randomBytes: (Int) -> ByteArray): ChunkKeys {
            val material = randomBytes(KEY_MATERIAL_BYTES)
            try {
                check(material.size == KEY_MATERIAL_BYTES) { "Unexpected random key size" }
                val encryptionKey = material.copyOf(ENCRYPTION_KEY_BYTES)
                val keys = try {
                    FileEncryptionFormat.requireAesCbc256HmacSha256Keys(encryptionKey)
                } finally {
                    encryptionKey.fill(0)
                }
                return ChunkKeys(
                    keys = keys,
                    ivKey = material.copyOfRange(ENCRYPTION_KEY_BYTES, KEY_MATERIAL_BYTES),
                )
            } finally {
                material.fill(0)
            }
        }
    }
}

/** Outlives the writer's operation, so it never consults the writer's cancellation probe. */
private class EncryptedChunkedByteSnapshot(
    private val storage: PrivateTemporaryStorage,
    private val keys: ChunkKeys,
    override val size: Long,
) : RandomAccessByteSnapshot {
    private var closed = false

    override fun openReader(): ByteSnapshotReader {
        checkOpen()
        return ChunkReader()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            storage.close()
        } finally {
            keys.clear()
        }
    }

    private fun checkOpen() {
        check(!closed) { "Encrypted chunked byte snapshot is closed" }
    }

    /** Holds at most one verified plaintext chunk. */
    private inner class ChunkReader : ByteSnapshotReader {
        private val frame = ByteArray(EncryptedChunkedSpillStorage.FRAME_STRIDE)
        private var plaintext: ByteArray? = null
        private var plaintextIndex = -1L
        private var readerClosed = false

        override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
            check(!readerClosed) { "Encrypted chunked byte reader is closed" }
            checkOpen()
            requireSnapshotReadRange(position, data, startIndex, endIndex)
            var cursor = startIndex
            var offset = position
            while (cursor < endIndex && offset < size) {
                val index = offset / EncryptedChunkedSpillStorage.CHUNK_BYTES
                val chunk = load(index)
                val within = (offset - index * EncryptedChunkedSpillStorage.CHUNK_BYTES).toInt()
                val length = minOf(endIndex - cursor, chunk.size - within)
                chunk.copyInto(data, cursor, within, within + length)
                cursor += length
                offset += length
            }
            return cursor - startIndex
        }

        private fun load(index: Long): ByteArray {
            plaintext?.let { if (plaintextIndex == index) return it }
            evict()
            val start = index * EncryptedChunkedSpillStorage.CHUNK_BYTES
            val plaintextLength = minOf(EncryptedChunkedSpillStorage.CHUNK_BYTES.toLong(), size - start).toInt()
            val frameLength = EncryptedChunkedSpillStorage.frameLength(plaintextLength)
            val ciphertextLength = frameLength - FileEncryptionFormat.MAC_LENGTH
            val read = storage.readAt(index * EncryptedChunkedSpillStorage.FRAME_STRIDE, frame, 0, frameLength)
            if (read != frameLength) throw IOException("Encrypted chunked spill is truncated")
            val ciphertext = frame.copyOf(ciphertextLength)
            val mac = frame.copyOfRange(ciphertextLength, frameLength)
            val iv = keys.iv(index)
            val decrypted = try {
                NativeCryptoPrimitives.aesCbcPkcs7HmacSha256Decrypt(
                    encryptionKey = keys.encKey,
                    macKey = keys.macKey,
                    iv = iv,
                    ciphertext = ciphertext,
                    expectedMac = mac,
                )
            } catch (e: NativeCryptoException) {
                throw IOException("Encrypted chunked spill authentication failed", e)
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
                mac.fill(0)
                frame.fill(0, 0, frameLength)
            }
            // Authentication already bound the frame to this index and length.
            if (decrypted.size != plaintextLength) decrypted.fill(0)
            check(decrypted.size == plaintextLength) { "Encrypted chunked spill has an unexpected chunk size" }
            plaintext = decrypted
            plaintextIndex = index
            return decrypted
        }

        private fun evict() {
            plaintext?.fill(0)
            plaintext = null
            plaintextIndex = -1L
        }

        override fun close() {
            if (readerClosed) return
            readerClosed = true
            evict()
        }
    }
}
