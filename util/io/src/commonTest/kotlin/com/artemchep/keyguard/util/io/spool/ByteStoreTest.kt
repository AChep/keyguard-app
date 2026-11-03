package com.artemchep.keyguard.util.io.spool

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteStoreTest {
    @Test
    fun buildSnapshotReturnsSealedBytesAndClosesTheWriter() {
        val writer = FakeByteStoreWriter()
        val snapshot = writer.buildSnapshot { sink ->
            sink.write(byteArrayOf(1, 2, 3))
        }
        assertTrue(writer.closed)
        snapshot.use {
            assertEquals(3L, it.size)
            assertContentEquals(byteArrayOf(1, 2, 3), it.readBytes())
        }
    }

    @Test
    fun buildSnapshotClosesTheWriterWhenWriteFails() {
        val writer = FakeByteStoreWriter()
        assertFailsWith<FakeIoFailure> {
            writer.buildSnapshot { throw FakeIoFailure() }
        }
        assertTrue(writer.closed)
        assertFalse(writer.sealed)
    }

    @Test
    fun stageToCopiesStagedBytesAndReturnsTheWriteResult() {
        val writer = FakeByteStoreWriter()
        val output = RecordingRawSink()
        val result = output.buffered().use { sink ->
            writer.stageTo(sink) { staging ->
                staging.write(byteArrayOf(4, 5, 6))
                "done"
            }
        }
        assertEquals("done", result)
        assertContentEquals(byteArrayOf(4, 5, 6), output.data.readByteArray())
        assertTrue(writer.closed)
        assertTrue(writer.snapshotClosed)
    }

    @Test
    fun stageToFlushesButDoesNotCloseTheOutput() {
        val writer = FakeByteStoreWriter()
        val output = RecordingRawSink()
        val sink = output.buffered()
        writer.stageTo(sink) { staging -> staging.write(byteArrayOf(7)) }
        assertTrue(output.flushed)
        assertFalse(output.closed)
    }

    @Test
    fun stageToReleasesEverythingWhenTheOutputFails() {
        val writer = FakeByteStoreWriter()
        val output = FailingRawSink().buffered()
        assertFailsWith<FakeIoFailure> {
            writer.stageTo(output) { staging -> staging.write(byteArrayOf(8)) }
        }
        assertTrue(writer.closed)
        assertTrue(writer.snapshotClosed)
    }

    @Test
    fun buildVerifiedSnapshotReleasesTheSnapshotOnlyAfterVerification() {
        val writer = FakeByteStoreWriter()
        var verifiedBytes: ByteArray? = null
        val snapshot = writer.buildVerifiedSnapshot(
            write = { sink -> sink.write(byteArrayOf(1, 2, 3)) },
            verify = { source -> verifiedBytes = source.readByteArray() },
        )
        assertContentEquals(byteArrayOf(1, 2, 3), verifiedBytes)
        assertTrue(writer.closed)
        snapshot.use {
            assertContentEquals(byteArrayOf(1, 2, 3), it.readBytes())
        }
    }

    @Test
    fun buildVerifiedSnapshotClosesTheSnapshotWhenVerificationFails() {
        val writer = FakeByteStoreWriter()
        assertFailsWith<FakeIoFailure> {
            writer.buildVerifiedSnapshot(
                write = { sink -> sink.write(byteArrayOf(9)) },
                verify = { throw FakeIoFailure() },
            )
        }
        assertTrue(writer.closed)
        assertTrue(writer.snapshotClosed, "unverified bytes must never escape")
    }

    @Test
    fun randomAccessSnapshotSourceReplaysThroughOneReader() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        var readerCloses = 0
        val snapshot = object : RandomAccessByteSnapshot {
            override val size = bytes.size.toLong()

            override fun openReader() = object : ByteSnapshotReader {
                override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
                    if (position >= bytes.size) return 0
                    val count = minOf(endIndex - startIndex, bytes.size - position.toInt())
                    bytes.copyInto(data, startIndex, position.toInt(), position.toInt() + count)
                    return count
                }

                override fun close() {
                    readerCloses += 1
                }
            }

            override fun close() = Unit
        }
        assertContentEquals(bytes, snapshot.readBytes())
        assertContentEquals(bytes, snapshot.readBytes())
        assertEquals(2, readerCloses)
    }
}
