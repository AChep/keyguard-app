package com.artemchep.keyguard.util.io.spool

import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source

private const val BYTE_SNAPSHOT_TRANSFER_BYTES = 64 * 1024
private const val MAX_CONSECUTIVE_ZERO_READS = 16

/** Copies this snapshot without taking ownership of [output]. */
fun ByteSnapshot.copyTo(output: Sink) {
    openSource().use { input ->
        input.copyTo(
            output = output,
            transfer = ByteArray(BYTE_SNAPSHOT_TRANSFER_BYTES),
        )
    }
}

private fun Source.copyTo(
    output: Sink,
    transfer: ByteArray,
) {
    var consecutiveZeroReads = 0
    try {
        var length = readAtMostTo(transfer)
        while (length >= 0) {
            if (length == 0) {
                consecutiveZeroReads += 1
                if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                    throw IOException("Source made no progress while reading")
                }
            } else {
                consecutiveZeroReads = 0
                output.write(transfer, 0, length)
            }
            length = readAtMostTo(transfer)
        }
    } finally {
        transfer.fill(0)
    }
}

/**
 * Runs [write] against this store's sink, then seals the store and returns the
 * snapshot. The store is always closed, even if [write] or the seal fails; the
 * caller owns the returned snapshot and must close it.
 */
inline fun ByteStoreWriter.buildSnapshot(write: (Sink) -> Unit): ByteSnapshot =
    use { writer ->
        writer.sink().use(write)
        writer.seal()
    }

/**
 * Runs [write] against this store's sink and, once it completes, copies the
 * staged bytes to [output]. Returns [write]'s result. [output] is flushed but
 * not closed; the store and snapshot are always released.
 */
inline fun <T> ByteStoreWriter.stageTo(
    output: Sink,
    write: (Sink) -> T,
): T = use { writer ->
    val result = writer.sink().use(write)
    writer.seal().use { snapshot ->
        snapshot.copyTo(output)
    }
    output.flush()
    result
}

/**
 * Runs [write] against this store's sink, seals the store, then runs
 * [verify] against a fresh source of the sealed bytes.
 *
 * The snapshot escapes only when verification succeeds: a failing [verify]
 * closes the snapshot and rethrows, so unverified bytes can never be
 * published. This is the stage → verify → release pattern used before
 * trusting an encode, a decrypt, or any other transformation whose output
 * must be proven readable before it replaces good data.
 *
 * [verify] is caller-provided, so cleanup must handle and rethrow every throwable.
 */
@Suppress("TooGenericExceptionCaught")
inline fun ByteStoreWriter.buildVerifiedSnapshot(
    write: (Sink) -> Unit,
    verify: (Source) -> Unit,
): ByteSnapshot {
    val snapshot = buildSnapshot(write)
    try {
        snapshot.openSource().use(verify)
        return snapshot
    } catch (error: Throwable) {
        runCatching { snapshot.close() }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
        throw error
    }
}
