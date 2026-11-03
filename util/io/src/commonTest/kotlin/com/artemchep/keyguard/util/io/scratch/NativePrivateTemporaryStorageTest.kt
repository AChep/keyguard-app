package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val BRIDGE_PANIC: Long = "8000000002030C00".toULong(16).toLong()

@OptIn(InternalKeyguardIoApi::class)
class NativePrivateTemporaryStorageTest {
    @Test
    fun sealFinalizesTheExactNativeSinkBeforeSealing() {
        val calls = RecordingCalls()
        val storage = storage(calls)
        storage.sink().write(
            source = Buffer().apply { write(byteArrayOf(1, 2, 3)) },
            byteCount = 3L,
        )

        storage.sealForReading()

        assertEquals(
            listOf("write:3", "seal"),
            calls.events,
        )
        storage.close()
    }

    @Test
    fun caughtWriteFailurePermanentlyPreventsSeal() {
        val calls = RecordingCalls(
            writeResult = { BRIDGE_PANIC },
        )
        val storage = storage(calls)
        val sink = storage.sink()
        sink.write(
            source = Buffer().apply { write(byteArrayOf(1, 2, 3)) },
            byteCount = 3L,
        )
        val writeFailure = assertFailsWith<FileSystemOperationException> {
            sink.flush()
        }

        val sealFailure = assertFailsWith<FileSystemOperationException> {
            storage.sealForReading()
        }

        assertTrue(sealFailure === writeFailure)
        assertEquals(1, calls.writeCount)
        assertEquals(0, calls.sealCount)
        storage.close()
    }

    @Test
    fun sealFailureIsTerminalAndPreservesItsFirstCause() {
        val calls = RecordingCalls(
            sealResult = BRIDGE_PANIC,
        )
        val storage = storage(calls)

        val first = assertFailsWith<FileSystemOperationException> {
            storage.sealForReading()
        }
        val second = assertFailsWith<FileSystemOperationException> {
            storage.sealForReading()
        }

        assertTrue(second === first)
        assertEquals(1, calls.sealCount)
        storage.close()
    }

    @Test
    fun closeDiscardsPendingBytesInsteadOfWritingThem() {
        val calls = RecordingCalls()
        val storage = storage(calls)
        storage.sink().write(
            source = Buffer().apply { write(byteArrayOf(1, 2, 3)) },
            byteCount = 3L,
        )

        storage.close()
        storage.close()

        assertEquals(0, calls.writeCount)
        assertEquals(0, calls.sealCount)
        assertEquals(1, calls.closeCount)
    }

    @Test
    fun emptyReadsRequireSealedStorageInNativeAndDefaultImplementations() {
        val calls = RecordingCalls()
        storage(calls).use { native ->
            val sourceBased = SourceBasedStorage(native)
            val implementations = listOf(native, sourceBased)
            val output = byteArrayOf(7, 8, 9)
            for (position in listOf(0L, 1L, Long.MAX_VALUE)) {
                for (implementation in implementations) {
                    assertFailsWith<IllegalStateException> {
                        implementation.readAt(position, output, 1, 1)
                    }
                    assertFailsWith<IllegalStateException> {
                        implementation.readAt(position, ByteArray(0))
                    }
                }
            }

            native.sealForReading()

            for (position in listOf(0L, 1L, Long.MAX_VALUE)) {
                for (implementation in implementations) {
                    for (index in 0..output.size) {
                        assertEquals(0, implementation.readAt(position, output, index, index))
                    }
                    assertEquals(0, implementation.readAt(position, ByteArray(0)))
                }
            }
            assertContentEquals(byteArrayOf(7, 8, 9), output)
            assertTrue(sourceBased.sourceOpenCount > 0)
            assertEquals(sourceBased.sourceOpenCount, sourceBased.sourceCloseCount)
            assertEquals(0, calls.readCount)

            native.close()

            for (position in listOf(0L, 1L, Long.MAX_VALUE)) {
                for (implementation in implementations) {
                    assertFailsWith<IllegalStateException> {
                        implementation.readAt(position, output, 1, 1)
                    }
                    assertFailsWith<IllegalStateException> {
                        implementation.readAt(position, ByteArray(0))
                    }
                }
            }
            assertEquals(0, calls.readCount)
        }
    }

    @Test
    fun emptyReadsRejectFailedStorageInNativeAndDefaultImplementations() {
        val calls = RecordingCalls(sealResult = BRIDGE_PANIC)
        storage(calls).use { native ->
            assertFailsWith<FileSystemOperationException> { native.sealForReading() }

            for (implementation in listOf(native, SourceBasedStorage(native))) {
                assertFailsWith<IllegalStateException> {
                    implementation.readAt(0, ByteArray(0))
                }
                assertFailsWith<IllegalStateException> {
                    implementation.readAt(Long.MAX_VALUE, byteArrayOf(7), 1, 1)
                }
            }
            assertEquals(0, calls.readCount)
        }
    }

    private fun storage(
        calls: NativePrivateTemporaryStorageCalls,
    ) = NativePrivateTemporaryStorage(
        handle = 11L,
        calls = calls,
    )

    /** Uses the interface's source-based readAt while sharing the native lifecycle. */
    private class SourceBasedStorage(
        private val delegate: PrivateTemporaryStorage,
    ) : PrivateTemporaryStorage {
        var sourceOpenCount = 0
        var sourceCloseCount = 0

        override fun sink() = delegate.sink()

        override fun sealForReading() = delegate.sealForReading()

        override fun source(): RawSource {
            val source = delegate.source()
            sourceOpenCount += 1
            return object : RawSource by source {
                override fun close() {
                    sourceCloseCount += 1
                    source.close()
                }
            }
        }

        override fun close() = delegate.close()
    }

    private class RecordingCalls(
        private val writeResult: (Int) -> Long = { it.toLong() },
        private val sealResult: Long = 0L,
    ) : NativePrivateTemporaryStorageCalls {
        val events = mutableListOf<String>()
        var writeCount = 0
        var sealCount = 0
        var closeCount = 0
        var readCount = 0

        override fun scratchWrite(
            handle: Long,
            input: ByteArray,
            offset: Int,
            length: Int,
        ): Long {
            events += "write:$length"
            writeCount += 1
            return writeResult(length)
        }

        override fun scratchSeal(handle: Long): Long {
            events += "seal"
            sealCount += 1
            return sealResult
        }

        override fun scratchReadAt(
            handle: Long,
            position: Long,
            output: ByteArray,
            offset: Int,
            length: Int,
        ): Long {
            readCount += 1
            return -1L
        }

        override fun scratchClose(handle: Long): Long {
            events += "close"
            closeCount += 1
            return 0L
        }
    }
}
