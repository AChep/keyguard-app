package com.artemchep.keyguard.util.foundation.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoContractTest {
    private val crypto = PlatformCryptoPrimitives()

    private val key32 = ByteArray(32) { it.toByte() }
    private val iv16 = ByteArray(16) { (it + 16).toByte() }

    @Test
    fun hkdfNegativeLengthThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.hkdfSha256(ByteArray(32), null, null, -1)
        }
    }

    @Test
    fun hkdfAboveCapThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.hkdfSha256(ByteArray(32), "00".hexToByteArray(), null, 255 * 32 + 1)
        }
    }

    @Test
    fun hkdfAtCapSucceeds() {
        assertEquals(
            255 * 32,
            crypto.hkdfSha256(ByteArray(32), "00".hexToByteArray(), null, 255 * 32).size,
        )
    }

    @Test
    fun pbkdf2ZeroIterationsThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.pbkdf2Sha256("p".encodeToByteArray(), "s".encodeToByteArray(), 0, 32)
        }
    }

    @Test
    fun pbkdf2NegativeIterationsThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.pbkdf2Sha256("p".encodeToByteArray(), "s".encodeToByteArray(), -1, 32)
        }
    }

    @Test
    fun pbkdf2NegativeLengthThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.pbkdf2Sha256("p".encodeToByteArray(), "s".encodeToByteArray(), 1, -1)
        }
    }

    @Test
    fun pbkdf2ZeroLengthReturnsEmpty() {
        assertTrue(
            crypto.pbkdf2Sha256("p".encodeToByteArray(), "s".encodeToByteArray(), 1, 0).isEmpty(),
        )
    }

    @Test
    fun randomBytesNegativeThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.randomBytes(-1)
        }
    }

    @Test
    fun randomIntZeroBoundThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.randomInt(0)
        }
    }

    @Test
    fun randomIntNegativeBoundThrows() {
        assertFailsWith<IllegalArgumentException> {
            crypto.randomInt(-5)
        }
    }

    @Test
    fun aesCbcPkcs7EmptyPlaintextRoundTrips() {
        val ct = crypto.aesCbcPkcs7Encrypt(key32, iv16, ByteArray(0))
        assertEquals(16, ct.size)
        assertContentEquals(ByteArray(0), crypto.aesCbcPkcs7Decrypt(key32, iv16, ct))
    }

    @Test
    fun aesEcbEmptyPlaintextReturnsEmpty() {
        assertTrue(crypto.aesEcbNoPaddingEncrypt(key32, ByteArray(0)).isEmpty())
    }

    @Test
    fun aesCbcPkcs7TamperedCiphertextNeverReturnsOriginal() {
        // KNOWN DIVERGENCE: JVM (JCE) throws BadPaddingException on a corrupted
        // ciphertext, but iOS (CommonCrypto one-shot CCCrypt) is lenient about
        // PKCS#7 validation and may return garbage instead of failing. The
        // cross-platform contract we CAN rely on is that a tampered ciphertext
        // must never decrypt back to the original plaintext.
        val data = "hello world".encodeToByteArray()
        val ct = crypto.aesCbcPkcs7Encrypt(key32, iv16, data)
        val bad = ct.copyOf()
        bad[bad.lastIndex] = (bad[bad.lastIndex].toInt() xor 0xff).toByte()
        val recovered = try {
            crypto.aesCbcPkcs7Decrypt(key32, iv16, bad)
        } catch (throwable: Throwable) {
            null
        }
        assertTrue(recovered == null || !recovered.contentEquals(data))
    }

    @Test
    fun aesEcbNonBlockMultipleThrows() {
        assertFails {
            crypto.aesEcbNoPaddingEncrypt(key32, ByteArray(5))
        }
    }

    @Test
    fun aesCbcBadKeySizeThrows() {
        assertFails {
            crypto.aesCbcPkcs7Encrypt(ByteArray(15), iv16, "x".encodeToByteArray())
        }
    }

    @Test
    fun aesCbcBadIvSizeThrows() {
        assertFails {
            crypto.aesCbcPkcs7Encrypt(key32, ByteArray(8), "x".encodeToByteArray())
        }
    }

    @Test
    fun hmacStateDoubleDoFinalThrows() {
        val s = createHmacSha256("k".encodeToByteArray())
        s.update("ab".encodeToByteArray(), 0, 2)
        s.doFinal()
        assertFailsWith<IllegalStateException> {
            s.doFinal()
        }
    }

    @Test
    fun hmacStateUpdateAfterDoFinalThrows() {
        val s = createHmacSha256("k".encodeToByteArray())
        s.update("ab".encodeToByteArray(), 0, 2)
        s.doFinal()
        assertFailsWith<IllegalStateException> {
            s.update("c".encodeToByteArray(), 0, 1)
        }
    }

    @Test
    fun hmacStateOutOfRangeUpdateThrows() {
        val s2 = createHmacSha256("k".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> {
            s2.update("ab".encodeToByteArray(), 0, 5)
        }
    }

    @Test
    fun hmacStateZeroLengthUpdateIsNoOp() {
        val key = "k".encodeToByteArray()
        val actual = createHmacSha256(key).also { it.update(ByteArray(0), 0, 0) }.doFinal()
        assertContentEquals(crypto.hmacSha256(key, ByteArray(0)), actual)
    }
}
