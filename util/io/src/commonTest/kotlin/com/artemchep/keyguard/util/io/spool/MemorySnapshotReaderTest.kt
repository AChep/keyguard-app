package com.artemchep.keyguard.util.io.spool

import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemorySnapshotReaderTest {
    @Test
    fun readsFollowTheSameContractRegardlessOfWritePartitioning() {
        for (size in listOf(0, 1, 17, 200_003)) {
            val payload = Random(size).nextBytes(size)
            for (writes in listOf(listOf(size.coerceAtLeast(1)), listOf(3, 7, 11), listOf(7, 29, 65_537))) {
                for (flush in listOf(false, true)) {
                    assertSnapshotReads(payload, writes, flush)
                }
            }
        }
    }

    private fun assertSnapshotReads(payload: ByteArray, writes: List<Int>, flush: Boolean) {
        memorySnapshot(payload, writes, flush).use { snapshot ->
            assertEquals(payload.size.toLong(), snapshot.size)
            snapshot.openReader().use { reader ->
                assertPositionalReadContract(payload, reader::readAt)
                assertInvalidPositionalReads(reader::readAt)
                assertReadsAroundWriteBoundaries(payload, writes, reader::readAt)
            }
        }
    }

    private fun assertReadsAroundWriteBoundaries(payload: ByteArray, writes: List<Int>, read: PositionalRead) {
        var boundary = 0
        for (writeSize in writes) {
            boundary += writeSize
            for (position in boundary - 1L..boundary + 1L) {
                assertPositionalRead(payload, read, position)
            }
        }
    }

    @Test
    fun readersAndSequentialSourcesAreIndependentAndDoNotOwnTheSnapshot() {
        val payload = Random(128).nextBytes(100_019)
        val snapshot = memorySnapshot(payload, listOf(7, 4093), flushEachWrite = true)
        snapshot.use {
            snapshot.openReader().use { first ->
                snapshot.openReader().use { second ->
                    snapshot.openSource().use { source ->
                        assertContentEquals(payload.copyOfRange(0, 13), source.readByteArray(13))
                        assertPositionalRead(payload, first::readAt, payload.size - 4L)
                        assertPositionalRead(payload, second::readAt, 100)
                        assertPositionalRead(payload, first::readAt, payload.size.toLong())
                        assertPositionalRead(payload, first::readAt, 0)
                        first.close()
                        first.close()
                        assertFailsWith<IllegalStateException> { first.readAt(0, ByteArray(1)) }
                        assertFailsWith<IllegalStateException> { first.readAt(0, ByteArray(0)) }
                        assertPositionalRead(payload, second::readAt, 7)
                        assertContentEquals(payload.copyOfRange(13, payload.size), source.readByteArray())
                    }
                }
            }
            snapshot.openReader().use { assertPositionalRead(payload, it::readAt, 0) }
        }
        snapshot.close()
        assertFailsWith<IllegalStateException> { snapshot.openReader() }
    }

    @Test
    fun callerArraysCannotMutateTheSealedBytes() {
        val payload = Random(514).nextBytes(80_003)
        val input = payload.copyOf()
        memorySnapshot(input, listOf(5, 8193), flushEachWrite = true).use { snapshot ->
            input.fill(0)
            snapshot.openReader().use { reader ->
                val output = ByteArray(payload.size)
                assertEquals(payload.size, reader.readAt(0, output))
                assertContentEquals(payload, output)
                output.fill(0)
                assertPositionalRead(payload, reader::readAt, 0, outputSize = payload.size + 13)
            }
        }
    }
}
