package com.artemchep.keyguard.util.foundation.io

import kotlinx.io.Sink
import kotlinx.io.buffered
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
        val snapshot = AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 8L,
            spillFactory = {
                spillCreated = true
                FakeByteStoreWriter()
            },
        ).use { spool ->
            spool.sink().use { sink -> sink.write(byteArrayOf(1, 2, 3, 4)) }
            assertFalse(spool.spilled)
            spool.seal()
        }

        snapshot.use {
            assertEquals(4L, snapshot.size)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), snapshot.readBytes())
        }
        assertFalse(spillCreated)
    }

    @Test
    fun memorySnapshotCanBeReadMoreThanOnce() {
        val payload = ByteArray(80_000) { index -> (index % 251).toByte() }
        val snapshot = AdaptiveSpool(
            memoryLimitBytes = payload.size.toLong(),
            maximumBytes = payload.size.toLong(),
            spillFactory = ::FakeByteStoreWriter,
        ).use { spool ->
            spool.sink().use { sink -> sink.write(payload) }
            spool.seal()
        }

        snapshot.use {
            assertContentEquals(payload, snapshot.readBytes())
            assertContentEquals(payload, snapshot.readBytes())
        }
    }

    @Test
    fun migratesExistingBytesWhenThresholdIsExceeded() {
        val writer = FakeByteStoreWriter()
        val snapshot = AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 16L,
            spillFactory = { writer },
        ).use { spool ->
            val sink = spool.sink()
            sink.write(byteArrayOf(1, 2, 3, 4))
            sink.write(byteArrayOf(5))
            sink.close()
            assertTrue(spool.spilled)
            assertEquals(5L, spool.size)
            spool.seal()
        }

        snapshot.use {
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), snapshot.readBytes())
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), snapshot.readBytes())
        }
        assertTrue(writer.sealed)
        assertTrue(writer.closed)
        assertTrue(writer.snapshotClosed)
    }

    @Test
    fun rejectsBytesBeyondMaximumBeforeAcceptingThem() {
        AdaptiveSpool(
            memoryLimitBytes = 2L,
            maximumBytes = 4L,
            spillFactory = ::FakeByteStoreWriter,
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
    }

    @Test
    fun sizeLimitFailurePoisonsTheSpool() {
        val spool = AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 4L,
            spillFactory = ::FakeByteStoreWriter,
            limitExceeded = { SpoolLimitFailure() },
        )
        var unexpectedSnapshot: ByteSnapshot? = null
        try {
            val sink = spool.sink()
            sink.write(byteArrayOf(1, 2, 3, 4))
            sink.flush()
            assertFailsWith<SpoolLimitFailure> {
                sink.use {
                    it.write(byteArrayOf(5))
                    it.flush()
                }
            }

            try {
                assertFailsWith<IllegalStateException> {
                    unexpectedSnapshot = spool.seal()
                }
            } finally {
                unexpectedSnapshot?.close()
            }
        } finally {
            spool.close()
        }
    }

    @Test
    fun sealingTransfersSnapshotOwnershipAndPreventsResealing() {
        val spool = AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 4L,
            spillFactory = ::FakeByteStoreWriter,
        )
        val snapshot = try {
            spool.sink().use { sink -> sink.write(byteArrayOf(1)) }
            val result = spool.seal()
            assertFailsWith<IllegalStateException> { spool.seal() }
            result
        } finally {
            spool.close()
        }

        snapshot.use {
            assertContentEquals(byteArrayOf(1), snapshot.readBytes())
        }
        assertFailsWith<IllegalStateException> { snapshot.openSource() }
    }

    @Test
    fun exposesOnlyOneWritableView() {
        AdaptiveSpool(
            memoryLimitBytes = 4L,
            maximumBytes = 4L,
            spillFactory = ::FakeByteStoreWriter,
        ).use { spool ->
            spool.sink()
            assertFailsWith<IllegalStateException> { spool.sink() }
        }
    }

    @Test
    fun failsSealWhenSpillSnapshotSizeMismatches() {
        var snapshotClosed = false
        AdaptiveSpool(
            memoryLimitBytes = 2L,
            maximumBytes = 16L,
            spillFactory = {
                val delegate = FakeByteStoreWriter()
                object : ByteStoreWriter by delegate {
                    override fun seal(): ByteSnapshot {
                        val snapshot = delegate.seal()
                        return object : ByteSnapshot by snapshot {
                            override val size: Long get() = snapshot.size + 1

                            override fun close() {
                                snapshotClosed = true
                                snapshot.close()
                            }
                        }
                    }
                }
            },
        ).use { spool ->
            spool.sink().use { sink -> sink.write(byteArrayOf(1, 2, 3, 4)) }
            assertFailsWith<IllegalStateException> { spool.seal() }
            assertTrue(snapshotClosed)
            assertFailsWith<IllegalStateException> { spool.seal() }
        }
    }

    @Test
    fun rejectsWritesAfterSeal() {
        AdaptiveSpool(
            memoryLimitBytes = 8L,
            maximumBytes = 8L,
            spillFactory = ::FakeByteStoreWriter,
        ).use { spool ->
            val sink = spool.sink()
            sink.write(byteArrayOf(1, 2))
            spool.seal().use { snapshot ->
                assertFailsWith<IllegalStateException> { sink.write(byteArrayOf(3)) }
                assertContentEquals(byteArrayOf(1, 2), snapshot.readBytes())
            }
        }
    }

    @Test
    fun spillWriteFailurePoisonsTheSpool() {
        val spool = AdaptiveSpool(
            memoryLimitBytes = 0L,
            maximumBytes = 1_000_000L,
            spillFactory = {
                object : ByteStoreWriter {
                    override fun sink(): Sink = FailingRawSink().buffered()

                    override fun seal(): ByteSnapshot = throw AssertionError()

                    override fun close() = Unit
                }
            },
        )
        val sink = spool.sink()
        assertFailsWith<FakeIoFailure> {
            sink.write(ByteArray(100_000))
            sink.flush()
        }
        assertFailsWith<IllegalStateException> { spool.seal() }
        // Bytes are still buffered in the input sink, yet closing the failed
        // spool must discard them instead of throwing.
        spool.close()
    }

    @Test
    fun closingAnAbandonedSpoolDiscardsBufferedBytes() {
        var writerCreated = false
        val spool = AdaptiveSpool(
            memoryLimitBytes = 2L,
            maximumBytes = 1_000_000L,
            spillFactory = {
                writerCreated = true
                FakeByteStoreWriter()
            },
        )
        spool.sink().write(ByteArray(5_000))
        spool.close()
        assertFalse(writerCreated)
    }

    private class SpoolLimitFailure : RuntimeException()
}
