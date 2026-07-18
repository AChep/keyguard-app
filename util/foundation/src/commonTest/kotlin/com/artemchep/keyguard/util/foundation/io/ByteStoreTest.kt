package com.artemchep.keyguard.util.foundation.io

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
}
