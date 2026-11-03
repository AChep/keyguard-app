package com.artemchep.keyguard.util.io.spool

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RandomAccessSnapshotSourceTest {
    @Test
    fun mixedReadSizesReplayAllBytesAndKeepReturningEof() {
        for (size in listOf(0, 1, 65_536, 140_019)) {
            val payload = Random(size).nextBytes(size)
            RecordingSnapshot(payload).use { snapshot ->
                snapshot.openSource().use { source ->
                    assertMixedReads(payload, source, snapshot.readers.single())
                    var position = 0L
                    snapshot.readers.single().reads.forEach { (offset, count) ->
                        assertEquals(position, offset)
                        position += count
                    }
                }
                assertEquals(1, snapshot.readers.single().closeCount)
                assertFalse(snapshot.closed)
            }
        }
    }

    private fun assertMixedReads(payload: ByteArray, source: Source, reader: RecordingReader) {
        // Source may report EOF for an empty request; it must not deliver any bytes.
        val emptyRead = Buffer()
        source.readAtMostTo(emptyRead, 0)
        assertEquals(0L, emptyRead.size)
        assertFailsWith<IllegalArgumentException> { source.readAtMostTo(Buffer(), -1) }
        val collected = Buffer()
        val readSizes = listOf(1, 7, 80_003, 3, 4093)
        var iteration = 0
        while (true) {
            val requested = readSizes[iteration++ % readSizes.size]
            val output = ByteArray(requested + 5) { 0x5a }
            val count = source.readAtMostTo(output, 2, requested + 2)
            if (count == -1) {
                assertContentEquals(ByteArray(output.size) { 0x5a }, output)
                break
            }
            assertTrue(count in 1..requested)
            val expected = ByteArray(output.size) { 0x5a }
            val position = collected.size.toInt()
            payload.copyInto(expected, 2, position, position + count)
            assertContentEquals(expected, output)
            collected.write(output, 2, 2 + count)
            assertTransferCleared(reader)
        }
        assertContentEquals(payload, collected.readByteArray())
        repeat(2) { assertEquals(-1, source.readAtMostTo(ByteArray(3))) }
        assertTransferCleared(reader)
    }

    @Test
    fun eachSourceOwnsOneIndependentReaderIncludingWhenNeverRead() {
        val payload = Random(98).nextBytes(100_007)
        RecordingSnapshot(payload).use { snapshot ->
            snapshot.openSource().use { first ->
                snapshot.openSource().use { second ->
                    assertEquals(2, snapshot.readers.size)
                    assertContentEquals(payload.copyOfRange(0, 3), first.readByteArray(3))
                    assertContentEquals(payload.copyOfRange(0, 7), second.readByteArray(7))
                    first.close()
                    first.close()
                    assertEquals(1, snapshot.readers[0].closeCount)
                    assertEquals(0, snapshot.readers[1].closeCount)
                    assertContentEquals(payload.copyOfRange(7, payload.size), second.readByteArray())
                }
            }
            snapshot.openSource().use { /* Closing an unread source must release its reader too. */ }
            assertEquals(3, snapshot.readers.size)
            assertTrue(snapshot.readers.last().reads.isEmpty())
            assertTrue(snapshot.readers.all { it.closeCount == 1 })
            assertFalse(snapshot.closed)
        }
    }

    @Test
    fun failedReadsClearPartiallyWrittenTransferBytesAndCloseTheReader() {
        for (failure in listOf(IOException("read failed"), AssertionError("read failed"))) {
            RecordingSnapshot(byteArrayOf(1), readFailure = failure).use { snapshot ->
                snapshot.openSource().use { source ->
                    assertSame(failure, assertFails { source.readByteArray() })
                    // Check before close: clearing only on close would retain plaintext between calls.
                    assertTransferCleared(snapshot.readers.single())
                }
                assertEquals(1, snapshot.readers.single().closeCount)
                assertFalse(snapshot.closed)
            }
        }
    }

    @Test
    fun closingFailureDoesNotReplaceTheReadFailure() {
        val readFailure = IOException("read failed")
        val closeFailure = IOException("close failed")
        RecordingSnapshot(byteArrayOf(1), readFailure, closeFailure).use { snapshot ->
            val thrown = assertFails { snapshot.openSource().use { it.readByteArray() } }
            assertSame(readFailure, thrown)
            assertEquals(listOf(closeFailure), thrown.suppressedExceptions)
            assertTransferCleared(snapshot.readers.single())
            assertEquals(1, snapshot.readers.single().closeCount)
            assertFalse(snapshot.closed)
        }
    }

    @Test
    fun readerCloseFailurePropagatesWithoutClosingTheSnapshot() {
        val failure = IOException("close failed")
        RecordingSnapshot(byteArrayOf(1), closeFailure = failure).use { snapshot ->
            val thrown = assertFails { snapshot.openSource().use { assertEquals(1.toByte(), it.readByte()) } }
            assertSame(failure, thrown)
            assertTransferCleared(snapshot.readers.single())
            assertEquals(1, snapshot.readers.single().closeCount)
            assertFalse(snapshot.closed)
        }
    }

    private fun assertTransferCleared(reader: RecordingReader) {
        val transfer = checkNotNull(reader.transfer)
        assertTrue(transfer.all { it == 0.toByte() }, "The borrowed transfer buffer retained bytes")
    }

    private class RecordingSnapshot(
        private val payload: ByteArray,
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : RandomAccessByteSnapshot {
        override val size = payload.size.toLong()
        val readers = mutableListOf<RecordingReader>()
        var closed = false

        override fun openReader(): ByteSnapshotReader =
            RecordingReader(payload, readFailure, closeFailure).also(readers::add)

        override fun close() {
            closed = true
        }
    }

    private class RecordingReader(
        private val payload: ByteArray,
        private val readFailure: Throwable?,
        private val closeFailure: Throwable?,
    ) : ByteSnapshotReader {
        val reads = mutableListOf<Pair<Long, Int>>()
        var transfer: ByteArray? = null
        var closeCount = 0

        override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
            check(closeCount == 0)
            transfer = data
            readFailure?.let {
                data.fill(0x7f, startIndex, endIndex)
                throw it
            }
            val count = minOf((endIndex - startIndex).toLong(), payload.size - position).coerceAtLeast(0).toInt()
            repeat(count) { data[startIndex + it] = payload[position.toInt() + it] }
            reads += position to count
            return count
        }

        override fun close() {
            closeCount += 1
            closeFailure?.let { throw it }
        }
    }
}
