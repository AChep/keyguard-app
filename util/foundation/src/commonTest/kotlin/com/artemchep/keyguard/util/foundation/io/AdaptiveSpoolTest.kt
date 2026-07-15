package com.artemchep.keyguard.util.foundation.io

import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveSpoolTest {
    @Test
    fun keepsPayloadAtOrBelowThresholdInMemory() {
        var spillCreated = false
        val output = Buffer()
        AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 8L,
            spillFactory = {
                spillCreated = true
                FakeSpillStorage()
            },
        ).use { spool ->
            spool.sink().use { sink -> sink.write(byteArrayOf(1, 2, 3, 4)) }
            spool.seal()
            assertFalse(spool.spilled)
            spool.replayTo(output)
        }

        assertFalse(spillCreated)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), output.readByteArray())
    }

    @Test
    fun keepsMultiSegmentPayloadInMemory() {
        val payload = ByteArray(80_000) { index -> (index % 251).toByte() }
        var spillCreated = false
        val output = Buffer()
        AdaptiveSpool(
            memoryLimitBytes = payload.size.toLong(),
            maximumBytes = payload.size.toLong(),
            spillFactory = {
                spillCreated = true
                FakeSpillStorage()
            },
        ).use { spool ->
            spool.sink().use { sink -> sink.write(payload) }
            spool.seal()
            assertFalse(spool.spilled)
            assertEquals(payload.size.toLong(), spool.size)
            spool.replayTo(output)
        }

        assertFalse(spillCreated)
        assertContentEquals(payload, output.readByteArray())
    }

    @Test
    fun migratesExistingBytesWhenThresholdIsExceeded() {
        val storage = FakeSpillStorage()
        val output = Buffer()
        AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 16L,
            spillFactory = { storage },
        ).use { spool ->
            val sink = spool.sink()
            sink.write(byteArrayOf(1, 2, 3, 4))
            sink.write(byteArrayOf(5))
            sink.close()
            spool.seal()
            assertTrue(spool.spilled)
            assertEquals(5L, spool.size)
            spool.replayTo(output)
        }

        assertTrue(storage.sealed)
        assertTrue(storage.closed)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), output.readByteArray())
    }

    @Test
    fun rejectsBytesBeyondMaximumBeforeAcceptingThem() {
        val output = Buffer()
        AdaptiveSpool(
            memoryLimitBytes = 2L,
            maximumBytes = 4L,
            spillFactory = ::FakeSpillStorage,
            limitExceeded = { SpoolLimitFailure() },
        ).use { spool ->
            val sink = spool.sink()
            sink.write(byteArrayOf(1, 2, 3, 4))
            sink.flush()
            assertFailsWith<SpoolLimitFailure> {
                sink.use {
                    it.write(byteArrayOf(5))
                    it.flush()
                }
            }
            assertEquals(4L, spool.size)
        }
        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun requiresSealAndAllowsOnlyOneReplay() {
        val spool = AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 4L,
            spillFactory = ::FakeSpillStorage,
        )
        try {
            spool.sink().use { sink -> sink.write(byteArrayOf(1)) }
            assertFailsWith<IllegalStateException> { spool.replayTo(Buffer()) }
            spool.seal()
            spool.replayTo(Buffer())
            assertFailsWith<IllegalStateException> { spool.replayTo(Buffer()) }
        } finally {
            spool.close()
        }
    }

    private class FakeSpillStorage : SpillStorage {
        private val buffer = Buffer()
        var sealed = false
        var closed = false

        override fun write(source: ByteArray, startIndex: Int, endIndex: Int) {
            check(!sealed)
            buffer.write(source, startIndex, endIndex)
        }

        override fun seal() {
            check(!sealed)
            sealed = true
        }

        override fun replayTo(output: Sink) {
            check(sealed)
            output.write(buffer.readByteArray())
        }

        override fun close() {
            closed = true
            val bytes = buffer.readByteArray()
            bytes.fill(0)
        }
    }

    private class SpoolLimitFailure : RuntimeException()
}
