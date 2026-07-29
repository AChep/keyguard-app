package com.artemchep.keyguard.util.io

import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.AtomicWriteReceipt
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Opens the file for reading; the caller owns the returned source and
 * usually wants to `buffered()` it.
 */
fun LocalPath.source(): RawSource =
    SystemFileSystem.source(toKotlinxIoPath())

/**
 * Deletes the file or empty directory; a missing path is not an error
 * unless [mustExist].
 */
fun LocalPath.delete(mustExist: Boolean = false) {
    SystemFileSystem.delete(toKotlinxIoPath(), mustExist = mustExist)
}

fun LocalPath.readText(): String =
    SystemFileSystem.source(toKotlinxIoPath())
        .buffered()
        .use { source ->
            source.readString()
        }

/**
 * Atomically replaces the file content with [data].
 *
 * There is deliberately no non-atomic write in this module: a convenience
 * write must never be the reason a file can tear. Namespace and
 * synchronization policies are explicit in [options].
 */
fun LocalPath.writeText(
    data: String,
    options: AtomicWriteOptions,
): AtomicWriteReceipt = writeBytes(data.encodeToByteArray(), options)

fun LocalPath.readBytes(): ByteArray =
    SystemFileSystem.source(toKotlinxIoPath())
        .buffered()
        .use { source ->
            source.readByteArray()
        }

/**
 * Atomically replaces the file content with [data]; see [writeText].
 */
fun LocalPath.writeBytes(
    data: ByteArray,
    options: AtomicWriteOptions,
): AtomicWriteReceipt =
    writeFileAtomically(
        destination = this,
        options = options,
    ) { sink ->
        sink.write(data)
    }.receipt
