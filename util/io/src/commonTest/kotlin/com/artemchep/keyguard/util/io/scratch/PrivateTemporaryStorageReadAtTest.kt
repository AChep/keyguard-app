package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.spool.assertInvalidPositionalReads
import com.artemchep.keyguard.util.io.spool.assertPositionalRead
import com.artemchep.keyguard.util.io.spool.assertPositionalReadContract
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame

@OptIn(InternalKeyguardIoApi::class)
class PrivateTemporaryStorageReadAtTest {
    @Test
    fun defaultReadsFillRangesFromFragmentedSourcesAndCloseEverySource() {
        for (size in listOf(0, 1, 10_007)) {
            val payload = Random(size).nextBytes(size)
            FragmentedStorage(payload).use { storage ->
                assertPositionalReadContract(payload, storage::readAt)
                assertEquals(storage.openCount, storage.sourceCloseCount)
                assertFalse(storage.closed)
            }
        }
    }

    @Test
    fun invalidArgumentsDoNotOpenASource() {
        FragmentedStorage(byteArrayOf(1)).use { storage ->
            assertInvalidPositionalReads(storage::readAt)
            assertEquals(0, storage.openCount)
        }
    }

    @Test
    fun errorsWhileSkippingOrFillingPropagateAndReleaseTheSource() {
        for (position in listOf(0L, 100L)) {
            for (failure in listOf(IOException("read failed"), AssertionError("read failed"))) {
                FragmentedStorage(ByteArray(200), failAt = 31, readFailure = failure).use { storage ->
                    val thrown = assertFails { storage.readAt(position, ByteArray(100)) }
                    assertSame(failure, thrown)
                    assertEquals(1, storage.openCount)
                    assertEquals(1, storage.sourceCloseCount)
                    assertFalse(storage.closed)
                }
            }
        }
    }

    @Test
    fun readFailureRemainsPrimaryWhenClosingAlsoFails() {
        val readFailure = IOException("read failed")
        val closeFailure = IOException("close failed")
        FragmentedStorage(
            ByteArray(100), failAt = 0, readFailure = readFailure, closeFailure = closeFailure,
        ).use { storage ->
            val thrown = assertFails { storage.readAt(0, ByteArray(10)) }
            assertSame(readFailure, thrown)
            assertEquals(listOf(closeFailure), thrown.suppressedExceptions)
            assertEquals(1, storage.sourceCloseCount)
        }
    }

    @Test
    fun sourceOpenAndCloseFailuresAreNotReportedAsEof() {
        val failure = IOException("source failed")
        FragmentedStorage(ByteArray(0), openFailure = failure).use { storage ->
            assertSame(failure, assertFails { storage.readAt(0, ByteArray(1)) })
            assertEquals(0, storage.sourceCloseCount)
        }
        for (position in listOf(0L, 100L)) {
            FragmentedStorage(ByteArray(10), closeFailure = failure).use { storage ->
                assertSame(failure, assertFails { storage.readAt(position, ByteArray(1)) })
                assertEquals(1, storage.sourceCloseCount)
            }
        }
    }

    @Test
    fun repeatedTailAndBackwardReadsUseFreshSources() {
        val payload = Random(77).nextBytes(103)
        FragmentedStorage(payload).use { storage ->
            for (position in listOf(100L, 103L, 104L, 100L, 0L, 32L, 1L)) {
                assertPositionalRead(payload, storage::readAt, position)
            }
            assertEquals(7, storage.openCount)
            assertEquals(7, storage.sourceCloseCount)
        }
    }

    /** Already-sealed storage whose sources can fail and never return more than 31 bytes. */
    private class FragmentedStorage(
        private val bytes: ByteArray,
        private val failAt: Int? = null,
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val openFailure: Throwable? = null,
    ) : PrivateTemporaryStorage {
        var openCount = 0
        var sourceCloseCount = 0
        var closed = false

        override fun sink(): Nothing = error("Already sealed")

        override fun sealForReading(): Nothing = error("Already sealed")

        override fun source(): RawSource {
            check(!closed)
            openFailure?.let { throw it }
            openCount += 1
            return object : RawSource {
                private var position = 0

                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                    if (failAt != null && position >= failAt) throw checkNotNull(readFailure)
                    if (byteCount == 0L || position == bytes.size) {
                        return if (byteCount == 0L) 0 else -1
                    }
                    val count = minOf(byteCount, 31L, (bytes.size - position).toLong()).toInt()
                    sink.write(bytes, position, position + count)
                    position += count
                    return count.toLong()
                }

                override fun close() {
                    sourceCloseCount += 1
                    closeFailure?.let { throw it }
                }
            }
        }

        override fun close() {
            closed = true
        }
    }
}
