package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import kotlinx.io.Sink
import kotlinx.io.write
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val COMMIT_PUBLISHED_NAMESPACE = 0x20L
private const val COMMIT_PUBLICATION_UNKNOWN_RENAME = 0x06000000000001F6L
private val BRIDGE_PANIC: Long = "8000000002030C00".toULong(16).toLong()

@OptIn(InternalKeyguardIoApi::class)
class NativeAtomicFileTransactionTest {
    @Test
    fun pendingBytesAreWrittenBeforeCommitConsumesTheHandle() {
        val calls = RecordingCalls()

        val result = transaction(calls).use { transaction ->
            transaction.writeAndCommit { sink ->
                sink.write(byteArrayOf(1, 2, 3))
                "written"
            }
        }

        assertEquals("written", result.value)
        assertEquals(
            listOf("write:3", "commit"),
            calls.events,
        )
        assertEquals(0, calls.abortCount)
    }

    @Test
    fun caughtNativeWriteFailureStillAbortsAndPreventsCommit() {
        val calls = RecordingCalls(
            writeResult = { BRIDGE_PANIC },
        )
        var caught = false

        assertFailsWith<AtomicFileWriteException> {
            transaction(calls).use { transaction ->
                transaction.writeAndCommit { sink ->
                    try {
                        sink.write(ByteArray(NATIVE_CHUNK_SIZE + 1))
                        sink.flush()
                    } catch (_: AtomicFileWriteException) {
                        // A callback cannot convert a failed native write into
                        // a committable transaction.
                        caught = true
                    }
                }
            }
        }

        assertTrue(caught)
        assertEquals(0, calls.commitCount)
        assertEquals(1, calls.abortCount)
    }

    @Test
    fun callbackFailureDiscardsBufferedBytesAndClosesEscapedSink() {
        val callbackFailure = TestException()
        val calls = RecordingCalls()
        var escapedSink: Sink? = null

        val thrown = assertFailsWith<TestException> {
            transaction(calls).use { transaction ->
                transaction.writeAndCommit<Unit> { sink ->
                    escapedSink = sink
                    sink.write(byteArrayOf(1, 2, 3))
                    throw callbackFailure
                }
            }
        }

        assertTrue(thrown === callbackFailure)
        assertEquals(listOf("abort"), calls.events)
        assertFailsWith<IllegalStateException> {
            requireNotNull(escapedSink).write(byteArrayOf(4))
        }
    }

    @Test
    fun writeFailureDuringOwnedCloseConsumesTrailingBufferedBytes() {
        val calls = RecordingCalls(
            writeResult = { BRIDGE_PANIC },
        )
        var escapedSink: Sink? = null

        assertFailsWith<AtomicFileWriteException> {
            transaction(calls).use { transaction ->
                transaction.writeAndCommit { sink ->
                    escapedSink = sink
                    sink.write(ByteArray(NATIVE_CHUNK_SIZE + 1))
                }
            }
        }

        assertEquals(
            listOf("write:$NATIVE_CHUNK_SIZE", "abort"),
            calls.events,
        )
        assertFailsWith<IllegalStateException> {
            requireNotNull(escapedSink).write(byteArrayOf(1))
        }
    }

    @Test
    fun explicitCloseDuringCallbackDiscardsAndClosesOwnedSink() {
        val calls = RecordingCalls()
        val transaction = transaction(calls)
        var escapedSink: Sink? = null

        assertFailsWith<IllegalStateException> {
            transaction.use {
                transaction.writeAndCommit { sink ->
                    escapedSink = sink
                    sink.write(byteArrayOf(1, 2, 3))
                    transaction.close()
                }
            }
        }

        assertEquals(listOf("abort"), calls.events)
        assertFailsWith<IllegalStateException> {
            requireNotNull(escapedSink).write(byteArrayOf(4))
        }
    }

    @Test
    fun callbackFailureRemainsPrimaryWhenAbortAlsoFails() {
        val callbackFailure = TestException()
        val calls = RecordingCalls(
            abortResult = BRIDGE_PANIC,
        )

        val thrown = assertFailsWith<TestException> {
            transaction(calls).use { transaction ->
                transaction.writeAndCommit<Unit> {
                    throw callbackFailure
                }
            }
        }

        assertTrue(thrown === callbackFailure)
        assertEquals(1, calls.abortCount)
        assertEquals(1, thrown.suppressedExceptions.size)
        assertTrue(thrown.suppressedExceptions.single() is AtomicFileWriteException)
    }

    @Test
    fun suspendingCancellationAbortsOnceWithoutCommit() {
        val cancellation = CancellationException("cancelled")
        val calls = RecordingCalls()
        var escapedSink: Sink? = null

        val outcome = runSuspend {
            transaction(calls).use { transaction ->
                transaction.writeAndCommitSuspending<Unit> { sink ->
                    escapedSink = sink
                    sink.write(byteArrayOf(1, 2, 3))
                    throw cancellation
                }
            }
        }
        val thrown = assertFailsWith<CancellationException> {
            outcome.getOrThrow()
        }

        assertTrue(thrown === cancellation)
        assertEquals(0, calls.commitCount)
        assertEquals(1, calls.abortCount)
        assertEquals(listOf("abort"), calls.events)
        assertFailsWith<IllegalStateException> {
            requireNotNull(escapedSink).write(byteArrayOf(4))
        }
    }

    @Test
    fun cancellationAfterCaughtWriteFailureRemainsPrimary() {
        val cancellation = CancellationException("cancelled")
        val calls = RecordingCalls(
            writeResult = { BRIDGE_PANIC },
        )

        val outcome = runSuspend {
            transaction(calls).use { transaction ->
                transaction.writeAndCommitSuspending<Unit> { sink ->
                    try {
                        sink.write(ByteArray(NATIVE_CHUNK_SIZE + 1))
                        sink.flush()
                    } catch (_: AtomicFileWriteException) {
                        // Cancellation must not be masked by the remembered
                        // terminal write failure.
                    }
                    throw cancellation
                }
            }
        }
        val thrown = assertFailsWith<CancellationException> {
            outcome.getOrThrow()
        }

        assertTrue(thrown === cancellation)
        assertTrue(thrown.suppressedExceptions.single() is AtomicFileWriteException)
        assertEquals(0, calls.commitCount)
        assertEquals(1, calls.abortCount)
    }

    @Test
    fun abortFailureUpgradesTypedWriteFailureCleanupState() {
        val calls = RecordingCalls(
            writeResult = { BRIDGE_PANIC },
            abortResult = BRIDGE_PANIC,
        )

        val thrown = assertFailsWith<AtomicFileWriteException> {
            transaction(calls).use { transaction ->
                transaction.writeAndCommit<Unit> { sink ->
                    sink.write(ByteArray(NATIVE_CHUNK_SIZE + 1))
                    sink.flush()
                }
            }
        }

        assertTrue(thrown.cleanupIncomplete)
        assertEquals(AtomicPublicationState.NotPublished, thrown.publicationState)
        assertTrue(thrown.cause is AtomicFileWriteException)
        assertTrue(thrown.suppressedExceptions.single() is AtomicFileWriteException)
        assertEquals(0, calls.commitCount)
        assertEquals(1, calls.abortCount)
    }

    @Test
    fun uncertainCommitConsumesHandleAndIsNeverFollowedByAbort() {
        val calls = RecordingCalls(
            commitResult = COMMIT_PUBLICATION_UNKNOWN_RENAME,
        )

        assertFailsWith<AtomicPublicationUnknownException> {
            transaction(calls).use { transaction ->
                transaction.writeAndCommit { Unit }
            }
        }

        assertEquals(1, calls.commitCount)
        assertEquals(0, calls.abortCount)
    }

    private fun transaction(
        calls: NativeAtomicFileTransactionCalls,
    ) = NativeAtomicFileTransaction(
        destination = LocalPath("/vault/store.kdbx"),
        handle = 7L,
        requestedSynchronization = SynchronizationPolicy.Required(
            SyncLevel.FileAndNamespaceSynchronized,
        ),
        calls = calls,
    )

    private class RecordingCalls(
        private val writeResult: (Int) -> Long = { it.toLong() },
        private val commitResult: Long = COMMIT_PUBLISHED_NAMESPACE,
        private val abortResult: Long = 0L,
    ) : NativeAtomicFileTransactionCalls {
        val events = mutableListOf<String>()
        var commitCount = 0
        var abortCount = 0

        override fun txnWrite(
            handle: Long,
            input: ByteArray,
            offset: Int,
            length: Int,
        ): Long {
            events += "write:$length"
            return writeResult(length)
        }

        override fun txnCommit(handle: Long): Long {
            events += "commit"
            commitCount += 1
            return commitResult
        }

        override fun txnAbort(handle: Long): Long {
            events += "abort"
            abortCount += 1
            return abortResult
        }
    }

    private class TestException : RuntimeException()

    private companion object {
        const val NATIVE_CHUNK_SIZE = 256 * 1024

        fun <T> runSuspend(
            block: suspend () -> T,
        ): Result<T> {
            var outcome: Result<T>? = null
            block.startCoroutine(
                object : Continuation<T> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<T>) {
                        outcome = result
                    }
                },
            )
            return requireNotNull(outcome) {
                "The test coroutine did not complete synchronously"
            }
        }
    }
}
