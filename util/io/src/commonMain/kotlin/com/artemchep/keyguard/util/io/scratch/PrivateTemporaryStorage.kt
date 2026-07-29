package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.bridge.NATIVE_IO_CHUNK_SIZE
import com.artemchep.keyguard.util.io.bridge.NativeChunkedRawSink
import com.artemchep.keyguard.util.io.bridge.NativeIo
import com.artemchep.keyguard.util.io.bridge.completeNativeIoOperation
import com.artemchep.keyguard.util.io.bridge.invalidNativeIoResult
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * Pathless private scratch storage with a write → seal → read-many
 * lifecycle.
 *
 * The backing file never has a reachable name on POSIX platforms (unlinked
 * at birth) and is owner-only, share-nothing, delete-on-close on Windows.
 * The lifecycle is enforced natively for safety; this wrapper mirrors it for
 * friendlier errors.
 *
 * The storage retains and finalizes the exact [RawSink] returned by [sink].
 * Additional wrappers around that sink must be closed before
 * [sealForReading], because their private buffers are not owned by the
 * storage.
 */
@InternalKeyguardIoApi
interface PrivateTemporaryStorage : AutoCloseable {
    /** Returns the only writable view of this storage. */
    fun sink(): RawSink

    /** Freezes the stored bytes for subsequent reads. */
    fun sealForReading()

    /** Returns a new source positioned at byte zero. */
    fun source(): RawSource
}

/**
 * Creates owner-only scratch storage inside the mandatory [directory].
 */
@InternalKeyguardIoApi
fun createPrivateTemporaryStorage(
    directory: LocalPath,
): PrivateTemporaryStorage {
    val packedHandle = completeNativeIoOperation(
        packedResult = NativeIo.scratchOpen(directory.value),
        subject = "scratchOpen",
    )
    if (packedHandle <= 0L) {
        throw invalidNativeIoResult(subject = "scratchOpen")
    }
    return NativePrivateTemporaryStorage(handle = packedHandle)
}

@InternalKeyguardIoApi
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal class NativePrivateTemporaryStorage(
    private val handle: Long,
    private val calls: NativePrivateTemporaryStorageCalls =
        DefaultNativePrivateTemporaryStorageCalls,
) : PrivateTemporaryStorage {
    private sealed interface State {
        data object Writable : State

        data class Failed(
            val cause: Throwable,
        ) : State

        data object Sealed : State

        data object Closed : State
    }

    private var state: State = State.Writable
    private var sinkTaken = false
    private var nativeSink: NativeChunkedRawSink? = null

    override fun sink(): RawSink {
        check(state === State.Writable) {
            "Private temporary storage is not writable"
        }
        check(!sinkTaken) {
            "Private temporary storage has only one writable view"
        }
        sinkTaken = true
        return NativeChunkedRawSink { chunk, length ->
            try {
                check(state === State.Writable) {
                    "Private temporary storage is not writable"
                }
                val result = completeNativeIoOperation(
                    packedResult = calls.scratchWrite(
                        handle = handle,
                        input = chunk,
                        offset = 0,
                        length = length,
                    ),
                    subject = "scratchWrite",
                )
                if (result != length.toLong()) {
                    throw invalidNativeIoResult(subject = "scratchWrite")
                }
            } catch (e: Throwable) {
                rememberFirstFailure(e)
                throw e
            }
        }.also {
            nativeSink = it
        }
    }

    override fun sealForReading() {
        failureOrNull()?.let { throw it }
        check(state === State.Writable) {
            "Private temporary storage is not writable"
        }
        try {
            nativeSink?.close()
        } catch (e: Throwable) {
            rememberFirstFailure(e)
            throw failureOrNull() ?: e
        }
        try {
            completeNativeIoOperation(
                packedResult = calls.scratchSeal(handle),
                subject = "scratchSeal",
            )
        } catch (e: Throwable) {
            rememberFirstFailure(e)
            throw failureOrNull() ?: e
        }
        state = State.Sealed
    }

    override fun source(): RawSource {
        check(state === State.Sealed) {
            "Private temporary storage must be sealed before reading"
        }
        return NativeScratchRawSource()
    }

    override fun close() {
        if (state === State.Closed) return
        nativeSink?.discard()
        state = State.Closed
        // A close failure is not actionable: the storage is pathless or
        // delete-on-close, so the operating system reclaims it regardless.
        calls.scratchClose(handle)
    }

    private fun rememberFirstFailure(cause: Throwable) {
        if (state === State.Writable) {
            state = State.Failed(cause)
        }
    }

    private fun failureOrNull(): Throwable? =
        (state as? State.Failed)?.cause

    private inner class NativeScratchRawSource : RawSource {
        private val chunk = ByteArray(NATIVE_IO_CHUNK_SIZE)
        private var position = 0L
        private var exhausted = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(state === State.Sealed) {
                "Private temporary storage is no longer readable"
            }
            if (exhausted || byteCount == 0L) {
                return if (exhausted) -1L else 0L
            }
            val step = minOf(byteCount, chunk.size.toLong()).toInt()
            try {
                val result = completeNativeIoOperation(
                    packedResult = calls.scratchReadAt(
                        handle = handle,
                        position = position,
                        output = chunk,
                        offset = 0,
                        length = step,
                    ),
                    subject = "scratchReadAt",
                )
                if (result == -1L) {
                    exhausted = true
                } else {
                    if (result <= 0L || result > step.toLong()) {
                        throw invalidNativeIoResult(subject = "scratchReadAt")
                    }
                    val read = result.toInt()
                    sink.write(chunk, 0, read)
                    position += read
                }
                return result
            } finally {
                chunk.fill(0)
            }
        }

        override fun close() {
            chunk.fill(0)
        }
    }
}

@InternalKeyguardIoApi
internal interface NativePrivateTemporaryStorageCalls {
    fun scratchWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    fun scratchSeal(handle: Long): Long

    fun scratchReadAt(
        handle: Long,
        position: Long,
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    fun scratchClose(handle: Long): Long
}

@InternalKeyguardIoApi
private object DefaultNativePrivateTemporaryStorageCalls :
    NativePrivateTemporaryStorageCalls {
    override fun scratchWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long = NativeIo.scratchWrite(
        handle = handle,
        input = input,
        offset = offset,
        length = length,
    )

    override fun scratchSeal(handle: Long): Long =
        NativeIo.scratchSeal(handle)

    override fun scratchReadAt(
        handle: Long,
        position: Long,
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Long = NativeIo.scratchReadAt(
        handle = handle,
        position = position,
        output = output,
        offset = offset,
        length = length,
    )

    override fun scratchClose(handle: Long): Long =
        NativeIo.scratchClose(handle)
}
