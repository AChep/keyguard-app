package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.bridge.NATIVE_IO_BRIDGE_INVALID_ARGUMENT
import com.artemchep.keyguard.util.io.bridge.NATIVE_IO_CHUNK_SIZE
import com.artemchep.keyguard.util.io.spool.assertInvalidPositionalReads
import com.artemchep.keyguard.util.io.spool.assertPositionalRead
import com.artemchep.keyguard.util.io.spool.assertPositionalReadContract
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(InternalKeyguardIoApi::class)
class NativePrivateTemporaryStorageReadAtTest {
    @Test
    fun readsFollowTheRangeContractEvenWhenNativeReadsAreShort() {
        for (size in listOf(0, 1, 600_019)) {
            val payload = Random(size).nextBytes(size)
            val calls = ReadCalls { request ->
                if (request.position >= payload.size) {
                    -1L
                } else {
                    val count = minOf(request.length, 4093, payload.size - request.position.toInt())
                    payload.copyInto(
                        request.output, request.offset, request.position.toInt(), request.position.toInt() + count,
                    )
                    count.toLong()
                }
            }
            sealedStorage(calls).use { storage ->
                assertPositionalReadContract(payload, storage::readAt)
            }
            assertEquals(1, calls.closeCount)
        }
    }

    @Test
    fun shortReadsAdvanceBothOffsetsAndDoNotReplayThePrefix() {
        val start = Int.MAX_VALUE + 123L
        val calls = ReadCalls { request ->
            val count = minOf(2, request.length)
            repeat(count) { request.output[request.offset + it] = (request.position - start + it + 1).toByte() }
            count.toLong()
        }
        sealedStorage(calls).use { storage ->
            val output = ByteArray(12) { 0x5a }
            assertEquals(5, storage.readAt(start, output, 3, 8))
            assertContentEquals(byteArrayOf(90, 90, 90, 1, 2, 3, 4, 5, 90, 90, 90, 90), output)
            assertEquals(listOf(start, start + 2, start + 4), calls.requests.map { it.position })
            assertEquals(listOf(3, 5, 7), calls.requests.map { it.offset })
            assertEquals(listOf(5, 3, 1), calls.requests.map { it.length })
        }
    }

    @Test
    fun largeRequestsUseBoundedContiguousNativeRanges() {
        val payload = Random(45).nextBytes(NATIVE_IO_CHUNK_SIZE * 2 + 71)
        val calls = ReadCalls { request ->
            assertTrue(request.length in 1..NATIVE_IO_CHUNK_SIZE)
            payload.copyInto(
                request.output, request.offset, request.position.toInt(), request.position.toInt() + request.length,
            )
            request.length.toLong()
        }
        sealedStorage(calls).use { storage ->
            assertPositionalRead(payload, storage::readAt, 5, payload.size + 10, 3, payload.size - 2)
            assertEquals(5L, calls.requests.first().position)
            calls.requests.zipWithNext().forEach { (previous, next) ->
                assertEquals(previous.position + previous.length, next.position)
                assertEquals(previous.offset + previous.length, next.offset)
            }
            assertEquals(payload.size - 5, calls.requests.sumOf { it.length })
        }
    }

    @Test
    fun partialReadFollowedByEofReturnsOnlyTheWrittenBytes() {
        var attempt = 0
        val calls = ReadCalls { request ->
            if (attempt++ == 0) {
                request.output[request.offset] = 7
                request.output[request.offset + 1] = 8
                2L
            } else {
                -1L
            }
        }
        sealedStorage(calls).use { storage ->
            val output = ByteArray(8) { 0x5a }
            assertEquals(2, storage.readAt(100, output, 2, 7))
            assertContentEquals(byteArrayOf(90, 90, 7, 8, 90, 90, 90, 90), output)
            assertEquals(listOf(100L, 102L), calls.requests.map { it.position })
        }
    }

    @Test
    fun nativeFailuresBeforeOrAfterProgressAreNotReportedAsEof() {
        for (initialProgress in listOf(false, true)) {
            var attempt = 0
            val calls = ReadCalls { request ->
                if (attempt++ == 0 && initialProgress) {
                    request.output[request.offset] = 7
                    1L
                } else {
                    NATIVE_IO_BRIDGE_INVALID_ARGUMENT
                }
            }
            sealedStorage(calls).use { storage ->
                val failure = assertFailsWith<FileSystemOperationException> { storage.readAt(0, ByteArray(4)) }
                assertEquals(FileSystemFailureKind.InvalidInput, failure.failure.kind)
                assertEquals(if (initialProgress) 2 else 1, calls.requests.size)
            }
        }
    }

    @Test
    fun zeroOversizedAndMalformedNativeResultsFailImmediately() {
        for (result in listOf(0L, 5L, Long.MAX_VALUE, -2L)) {
            val calls = ReadCalls { result }
            sealedStorage(calls).use { storage ->
                val failure = assertFailsWith<FileSystemOperationException> { storage.readAt(0, ByteArray(4)) }
                assertEquals(FileSystemFailureKind.Internal, failure.failure.kind)
                assertEquals(1, calls.requests.size)
            }
        }
    }

    @Test
    fun invalidArgumentsAndUnreadableStorageDoNotCallNativeReading() {
        val calls = ReadCalls { error("Native read must not be called") }
        NativePrivateTemporaryStorage(handle = 11, calls = calls).use { storage ->
            assertFailsWith<IllegalStateException> { storage.readAt(0, ByteArray(1)) }
            storage.sealForReading()
            assertInvalidPositionalReads(storage::readAt)
            storage.close()
            assertFailsWith<IllegalStateException> { storage.readAt(0, ByteArray(1)) }
        }
        assertTrue(calls.requests.isEmpty())
    }

    private fun sealedStorage(calls: ReadCalls) =
        NativePrivateTemporaryStorage(handle = 11, calls = calls).apply { sealForReading() }

    private data class ReadRequest(
        val position: Long,
        val output: ByteArray,
        val offset: Int,
        val length: Int,
    )

    private class ReadCalls(
        private val read: (ReadRequest) -> Long,
    ) : NativePrivateTemporaryStorageCalls {
        val requests = mutableListOf<ReadRequest>()
        var closeCount = 0

        override fun scratchWrite(handle: Long, input: ByteArray, offset: Int, length: Int): Long =
            error("Only reads are expected")

        override fun scratchSeal(handle: Long): Long = 0

        override fun scratchReadAt(handle: Long, position: Long, output: ByteArray, offset: Int, length: Int): Long {
            assertEquals(11L, handle)
            val request = ReadRequest(position, output, offset, length)
            requests += request
            return read(request)
        }

        override fun scratchClose(handle: Long): Long {
            closeCount += 1
            return 0
        }
    }
}
