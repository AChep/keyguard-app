package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.bridge.NativeChunkedRawSink
import com.artemchep.keyguard.util.io.bridge.NativeIo
import com.artemchep.keyguard.util.io.bridge.invalidNativeIoResult
import com.artemchep.keyguard.util.io.bridge.isNativeIoFailure
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlin.coroutines.cancellation.CancellationException

/**
 * A same-directory file transaction whose staged bytes are not published at
 * [destination] until [writeAndCommit] reaches its publication step.
 *
 * The complete crash-safety protocol — staging, flushing, renaming, and the
 * directory flush — runs inside the native core. The transaction owns the
 * writable view so that it can always finish or discard buffered bytes before
 * choosing exactly one terminal operation.
 *
 * Transactions are single-use and are not thread-safe. The [Sink] passed to a
 * callback must not escape it. Any additional sink wrappers created by the
 * callback must be closed before the callback returns.
 */
@InternalKeyguardIoApi
interface AtomicFileTransaction : AutoCloseable {
    val destination: LocalPath

    /**
     * Writes and publishes the staged bytes according to the requested
     * publication policy.
     *
     * The transaction closes its sink before committing. A callback or write
     * failure aborts the native transaction. A commit failure is reported as
     * [AtomicFileWriteException], whose
     * [AtomicFileWriteException.publicationState] tells the caller whether
     * the destination contains, does not contain, or may contain the staged
     * bytes.
     */
    fun <T> writeAndCommit(write: (Sink) -> T): AtomicWriteResult<T>

    /**
     * Suspending counterpart of [writeAndCommit].
     *
     * Cancellation is treated as a callback failure: the native transaction
     * is aborted before the cancellation exception is rethrown.
     */
    suspend fun <T> writeAndCommitSuspending(
        write: suspend (Sink) -> T,
    ): AtomicWriteResult<T>
}

/**
 * Opens a native atomic-write transaction targeting [destination].
 *
 * [destination] must be an absolute local path without parent (`..`)
 * components.
 *
 * The protocol — staging, flushing, renaming, and the directory flush — runs
 * entirely inside the native core; the returned transaction only shuttles
 * bytes and decodes results, identically on every platform.
 */
@InternalKeyguardIoApi
fun openAtomicFileTransaction(
    destination: LocalPath,
    options: AtomicWriteOptions,
): AtomicFileTransaction {
    val packedHandle = NativeIo.txnBegin(
        destination = destination.value,
        options = nativeIoTxnOptions(options),
    )
    if (isNativeIoFailure(packedHandle) || packedHandle <= 0L) {
        throwNativeIoTransactionFailure(
            packedResult = packedHandle,
            destination = destination,
        )
    }
    return NativeAtomicFileTransaction(
        destination = destination,
        handle = packedHandle,
        requestedSynchronization = options.synchronization,
    )
}

@InternalKeyguardIoApi
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal class NativeAtomicFileTransaction(
    override val destination: LocalPath,
    private val handle: Long,
    private val requestedSynchronization: SynchronizationPolicy,
    private val calls: NativeAtomicFileTransactionCalls = DefaultNativeAtomicFileTransactionCalls,
) : AtomicFileTransaction {
    private sealed interface State {
        data object Open : State

        data object Writing : State

        data class Failed(
            val cause: Throwable,
        ) : State

        data object HandleConsumed : State

        data object Closed : State
    }

    private var state: State = State.Open
    private val nativeSink = NativeChunkedRawSink { chunk, length ->
        try {
            check(state === State.Writing) {
                "Atomic file transaction is no longer writable"
            }
            val result = calls.txnWrite(
                handle = handle,
                input = chunk,
                offset = 0,
                length = length,
            )
            if (isNativeIoFailure(result)) {
                throwNativeIoTransactionFailure(
                    packedResult = result,
                    destination = destination,
                )
            }
            if (result != length.toLong()) {
                throw invalidNativeIoResult(subject = "txnWrite")
            }
        } catch (e: Throwable) {
            rememberFirstFailure(e)
            throw e
        }
    }
    private val sink = nativeSink.buffered()

    override fun <T> writeAndCommit(
        write: (Sink) -> T,
    ): AtomicWriteResult<T> {
        beginWriting()
        val value = try {
            write(sink)
        } catch (e: Throwable) {
            abortAndThrow(e)
        }
        return finishWriting(value)
    }

    override suspend fun <T> writeAndCommitSuspending(
        write: suspend (Sink) -> T,
    ): AtomicWriteResult<T> {
        beginWriting()
        val value = try {
            write(sink)
        } catch (e: Throwable) {
            abortAndThrow(e)
        }
        return finishWriting(value)
    }

    private fun beginWriting() {
        check(state === State.Open) {
            "Atomic file transaction was already completed"
        }
        state = State.Writing
    }

    private fun <T> finishWriting(value: T): AtomicWriteResult<T> {
        failureOrNull()?.let(::abortAndThrow)
        check(state === State.Writing) {
            "Atomic file transaction is no longer writable"
        }
        try {
            sink.close()
        } catch (e: Throwable) {
            abortAndThrow(e)
        }
        failureOrNull()?.let(::abortAndThrow)
        check(state === State.Writing) {
            "Atomic file transaction is no longer writable"
        }

        // The native commit consumes the handle on every result.
        state = State.HandleConsumed
        val packedResult = calls.txnCommit(handle)
        val receipt = completeNativeIoCommit(
            packedResult = packedResult,
            destination = destination,
            requestedSynchronization = requestedSynchronization,
        )
        return AtomicWriteResult(
            value = value,
            receipt = receipt,
        )
    }

    override fun close() {
        when (state) {
            State.HandleConsumed,
            State.Closed,
            -> Unit

            State.Open,
            State.Writing,
            is State.Failed,
            -> {
                var failure = discardOwnedSink()
                state = State.Closed
                try {
                    abortNativeHandle()
                } catch (cleanupFailure: Throwable) {
                    failure = failure?.withAbortCleanupFailure(cleanupFailure)
                        ?: cleanupFailure
                }
                failure?.let { throw it }
            }
        }
    }

    private fun failureOrNull(): Throwable? =
        (state as? State.Failed)?.cause

    private fun rememberFirstFailure(cause: Throwable) {
        when (state) {
            State.Writing -> state = State.Failed(cause)

            is State.Failed -> Unit

            State.Open,
            State.HandleConsumed,
            State.Closed,
            -> Unit
        }
    }

    private fun abortAndThrow(cause: Throwable): Nothing {
        var primary = selectPrimaryFailure(cause)
        when (state) {
            State.HandleConsumed,
            State.Closed,
            -> throw primary

            State.Open,
            State.Writing,
            is State.Failed,
            -> {
                discardOwnedSink()?.let { discardFailure ->
                    if (discardFailure !== primary) {
                        primary.addSuppressed(discardFailure)
                    }
                }
                state = State.Closed
                try {
                    abortNativeHandle()
                } catch (cleanupFailure: Throwable) {
                    primary = primary.withAbortCleanupFailure(cleanupFailure)
                }
                throw primary
            }
        }
    }

    private fun selectPrimaryFailure(cause: Throwable): Throwable {
        val remembered = failureOrNull()
        rememberFirstFailure(cause)
        if (remembered == null || remembered === cause) return cause
        return if (cause is CancellationException || cause is Error) {
            cause.addSuppressed(remembered)
            cause
        } else {
            remembered.addSuppressed(cause)
            remembered
        }
    }

    private fun discardOwnedSink(): Throwable? {
        var failure: Throwable? = null
        nativeSink.beginDiscarding()
        try {
            sink.close()
        } catch (e: Throwable) {
            failure = e
        } finally {
            nativeSink.discard()
        }
        return failure
    }

    private fun abortNativeHandle() {
        val packedResult = calls.txnAbort(handle)
        if (isNativeIoFailure(packedResult)) {
            throwNativeIoTransactionFailure(
                packedResult = packedResult,
                destination = destination,
            )
        }
        if (packedResult != 0L) {
            throw invalidNativeIoResult(subject = "txnAbort")
        }
    }
}

private fun Throwable.withAbortCleanupFailure(
    cleanupFailure: Throwable,
): Throwable {
    var result: Throwable = this
    if (cleanupFailure !== this) {
        if (this is AtomicFileWriteException && !cleanupIncomplete) {
            result = when (this) {
                is AtomicDestinationExistsException -> {
                    val cleanupFileSystemFailure =
                        (cleanupFailure as? FileSystemOperationException)?.failure
                    if (cleanupFileSystemFailure == null) {
                        copyAsCleanupIncomplete()
                    } else {
                        AtomicDestinationExistsException(
                            message = message ?: "Atomic destination exists",
                            cause = this,
                            cleanupFailure = cleanupFileSystemFailure,
                        )
                    }
                }

                is AtomicPublicationUnsupportedException ->
                    AtomicPublicationUnsupportedException(
                        message = message ?: "Atomic publication is unsupported",
                        cause = this,
                        diagnostic = failure.diagnostic,
                        cleanupIncomplete = true,
                    )

                is AtomicPublicationUnknownException ->
                    AtomicPublicationUnknownException(
                        message = message ?: "Atomic publication outcome is unknown",
                        cause = this,
                        publicationOperation = publicationOperation,
                        cleanupIncomplete = true,
                        failure = failure,
                    )

                is AtomicSynchronizationException ->
                    AtomicSynchronizationException(
                        message = message ?: "Atomic synchronization failed",
                        cause = this,
                        achievedSyncLevel = achievedSyncLevel,
                        cleanupIncomplete = true,
                        failure = failure,
                    )

                else ->
                    copyAsCleanupIncomplete()
            }
        }
        result.addSuppressed(cleanupFailure)
    }
    return result
}

private fun AtomicFileWriteException.copyAsCleanupIncomplete() =
    AtomicFileWriteException(
        message = message ?: "Atomic file write failed",
        cause = this,
        publicationState = publicationState.withCleanupIncomplete(),
        cleanupIncomplete = true,
        failure = failure,
    )

private fun AtomicPublicationState.withCleanupIncomplete() =
    if (this == AtomicPublicationState.PublishedSyncUnknown) {
        AtomicPublicationState.PublishedSyncUnknownCleanupIncomplete
    } else {
        this
    }

@InternalKeyguardIoApi
internal interface NativeAtomicFileTransactionCalls {
    fun txnWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    fun txnCommit(handle: Long): Long

    fun txnAbort(handle: Long): Long
}

@InternalKeyguardIoApi
private object DefaultNativeAtomicFileTransactionCalls : NativeAtomicFileTransactionCalls {
    override fun txnWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long = NativeIo.txnWrite(
        handle = handle,
        input = input,
        offset = offset,
        length = length,
    )

    override fun txnCommit(handle: Long): Long =
        NativeIo.txnCommit(handle)

    override fun txnAbort(handle: Long): Long =
        NativeIo.txnAbort(handle)
}
