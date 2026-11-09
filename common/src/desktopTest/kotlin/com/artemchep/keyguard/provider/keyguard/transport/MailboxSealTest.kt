package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.crypto.FileEncryptionFormat
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.crypto.NativeFileCrypto
import com.artemchep.keyguard.feature.p2psync.session.MessagePadding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How [MailboxSeal.open] tells an item it can not read from one that is broken. */
class MailboxSealTest {
    private val cryptoGenerator = NativeCryptoGenerator()
    private val key = ByteArray(MailboxSeal.KEY_SIZE) { it.toByte() }
    private val otherKey = ByteArray(MailboxSeal.KEY_SIZE) { (it * 3).toByte() }
    private val plaintext = "the words of a wordlist".encodeToByteArray()

    /** 10 000 zeros: a few bytes once compressed. */
    private val large = ByteArray(10_000)

    private fun seal(bytes: ByteArray = plaintext) = MailboxSeal.seal(bytes, key, cryptoGenerator)

    /** Encrypts [padded] as is, with a valid MAC: what a device with the key could write. */
    private fun encrypt(padded: ByteArray): ByteArray =
        NativeFileCrypto.encode(padded, key, cryptoGenerator.seed(length = FileEncryptionFormat.IV_LENGTH))

    private fun open(sealed: ByteArray, maxSize: Long = 1024) = MailboxSeal.open(sealed, key, maxSize)

    @Test
    fun `an item opens with its key`() {
        assertContentEquals(plaintext, assertIs<SealOpening.Opened>(open(seal())).plaintext)
    }

    @Test
    fun `an item of a cipher this build does not know is unsupported`() {
        listOf(0, 3, 0x7F, 0xFF).forEach { type ->
            val sealed = seal().also { it[0] = type.toByte() }
            assertEquals(SealOpening.Unsupported, open(sealed), "type $type")
        }
    }

    @Test
    fun `an authentic item larger than this build takes is too large, not corrupt`() {
        val sealed = seal(large)
        assertTrue(sealed.size < 1024, "The item is ${sealed.size} bytes")
        assertEquals(SealOpening.TooLarge, open(sealed, maxSize = large.size - 1L))
        assertContentEquals(large, assertIs<SealOpening.Opened>(open(sealed, maxSize = large.size.toLong())).plaintext)
    }

    @Test
    fun `an item that fails its MAC is corrupt, however large it would open`() {
        val sealed = seal(large)
        assertEquals(SealOpening.Corrupt, MailboxSeal.open(sealed, otherKey, maxSize = 1024))
        for (index in 1 until sealed.size) {
            val flipped = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(SealOpening.Corrupt, open(flipped), "byte $index")
        }
        assertEquals(SealOpening.Corrupt, open(sealed.copyOf(sealed.size - 1)))
        assertEquals(SealOpening.Corrupt, open(sealed.copyOf(1)))
        assertEquals(SealOpening.Corrupt, open(ByteArray(0)))
    }

    @Test
    fun `an authentic item with a broken padding or gzip stream is corrupt`() {
        // A length prefix past the end of the padding.
        assertEquals(SealOpening.Corrupt, open(encrypt(ByteArray(32) { 0x7F })))
        assertEquals(SealOpening.Corrupt, open(encrypt(MessagePadding.pad("not gzip".encodeToByteArray()))))
        val cut = GzipCompressor.compress(plaintext).let { it.copyOf(it.size - 4) }
        assertEquals(SealOpening.Corrupt, open(encrypt(MessagePadding.pad(cut))))
    }
}
