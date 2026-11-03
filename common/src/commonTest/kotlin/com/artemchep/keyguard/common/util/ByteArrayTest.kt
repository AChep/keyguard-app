package com.artemchep.keyguard.common.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ByteArrayTest {
    @Test
    fun `toHex encodes bytes as lowercase hex`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte())

        assertEquals("000f10ff", bytes.toHex())
    }

    @Test
    fun `hexToByteArray decodes uppercase and lowercase hex`() {
        val bytes = "000F10ff".hexToByteArray()

        assertContentEquals(
            byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte()),
            bytes,
        )
    }

    @Test
    fun `hexToByteArray rejects odd length hex`() {
        assertFailsWith<IllegalArgumentException> {
            "f".hexToByteArray()
        }
    }

    @Test
    fun `useAndClear returns the result and clears the buffer`() {
        val bytes = byteArrayOf(1, 2, 3)

        val result = bytes.useAndClear { it.sum() }

        assertEquals(6, result)
        assertTrue(bytes.isZeroed())
    }

    @Test
    fun `useAndClear clears the buffer when the block fails`() {
        val bytes = byteArrayOf(1, 2, 3)

        assertFailsWith<IllegalStateException> {
            bytes.useAndClear { error("failed") }
        }
        assertTrue(bytes.isZeroed())
    }

    @Test
    fun `useAndClear clears the buffer when a suspended block is cancelled`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val started = CompletableDeferred<Unit>()

        val job = launch {
            bytes.useAndClear {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(bytes.isZeroed())
    }
}

private fun ByteArray.isZeroed() = all { it == 0.toByte() }
