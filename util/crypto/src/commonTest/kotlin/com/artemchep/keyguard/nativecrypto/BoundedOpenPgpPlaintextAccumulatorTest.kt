package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedOpenPgpPlaintextAccumulatorTest {
    @Test
    fun commitReturnsPlaintextAndErasesStagedChunks() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)
        val accumulator = BoundedOpenPgpPlaintextAccumulator(
            operation = OPERATION,
            maximumBytes = 5,
        )

        val result = try {
            accumulator.stage(first)
            accumulator.stage(second)
            accumulator.commit()
        } finally {
            accumulator.close()
        }

        try {
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), result)
            assertTrue(first.all { byte -> byte == 0.toByte() })
            assertTrue(second.all { byte -> byte == 0.toByte() })
        } finally {
            result.fill(0)
        }
    }

    @Test
    fun overflowErasesTheExistingAndRejectedChunks() {
        val first = byteArrayOf(1, 2, 3)
        val rejected = byteArrayOf(4, 5, 6)
        val accumulator = BoundedOpenPgpPlaintextAccumulator(
            operation = OPERATION,
            maximumBytes = 5,
        )
        try {
            accumulator.stage(first)

            val failure = assertFailsWith<NativeCryptoException> {
                accumulator.stage(rejected)
            }

            assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
            assertTrue(first.all { byte -> byte == 0.toByte() })
            assertTrue(rejected.all { byte -> byte == 0.toByte() })
        } finally {
            accumulator.close()
        }
    }

    @Test
    fun closeErasesUncommittedPlaintext() {
        val plaintext = byteArrayOf(1, 2, 3)
        val accumulator = BoundedOpenPgpPlaintextAccumulator(
            operation = OPERATION,
            maximumBytes = plaintext.size,
        )

        accumulator.stage(plaintext)
        accumulator.close()

        assertTrue(plaintext.all { byte -> byte == 0.toByte() })
    }

    private companion object {
        const val OPERATION = "open_pgp_decrypt"
    }
}
