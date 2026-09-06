@file:OptIn(ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.zip.bridge

import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_abi_version
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_reader_close
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_reader_next_entry
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_reader_open
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_reader_read
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_writer_abort
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_writer_begin_entry
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_writer_end_entry
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_writer_finish
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_writer_open
import com.artemchep.keyguard.util.zip.ffi.keyguard_zip_writer_write
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.text.CharacterCodingException

internal actual object NativeZip {
    actual fun abiVersion(): Int = keyguard_zip_abi_version().toInt()

    actual fun open(path: String, password: String): Long =
        openWith(path, password) { p, pathSize, w, passwordSize ->
            keyguard_zip_writer_open(p, pathSize, w, passwordSize)
        }

    actual fun beginEntry(handle: Long, name: String): Long {
        val nameBytes = name.strictUtf8OrNull()
            ?: return NATIVE_ZIP_BRIDGE_INVALID_ARGUMENT
        return nameBytes.withNativePointer { pointer, size ->
            keyguard_zip_writer_begin_entry(
                handle.toULong(),
                pointer,
                size,
            )
        }
    }

    actual fun write(handle: Long, bytes: ByteArray, offset: Int, count: Int): Long =
        bytes.withNativeRange(offset, count) { pointer, size ->
            keyguard_zip_writer_write(handle.toULong(), pointer, size)
        }

    actual fun endEntry(handle: Long): Long =
        keyguard_zip_writer_end_entry(handle.toULong())

    actual fun finish(handle: Long): Long =
        keyguard_zip_writer_finish(handle.toULong())

    actual fun abort(handle: Long): Long =
        keyguard_zip_writer_abort(handle.toULong())

    actual fun readerOpen(path: String, password: String): Long =
        openWith(path, password) { p, pathSize, w, passwordSize ->
            keyguard_zip_reader_open(p, pathSize, w, passwordSize)
        }

    actual fun readerNextEntry(handle: Long, nameBuffer: ByteArray): Long =
        nameBuffer.withNativePointer { pointer, size ->
            keyguard_zip_reader_next_entry(
                handle.toULong(),
                pointer,
                size,
            )
        }

    actual fun readerRead(handle: Long, buffer: ByteArray, offset: Int, count: Int): Long =
        buffer.withNativeRange(offset, count) { pointer, size ->
            keyguard_zip_reader_read(handle.toULong(), pointer, size)
        }

    actual fun readerClose(handle: Long): Long =
        keyguard_zip_reader_close(handle.toULong())
}

/** Shared by the writer's and the reader's `*_open`, which have one signature. */
private inline fun openWith(
    path: String,
    password: String,
    open: (CPointer<UByteVar>?, ULong, CPointer<UByteVar>?, ULong) -> Long,
): Long {
    val pathBytes = path.strictUtf8OrNull()
    val passwordBytes = password.strictUtf8OrNull()
    if (pathBytes == null || passwordBytes == null) {
        return NATIVE_ZIP_BRIDGE_INVALID_ARGUMENT
    }
    return pathBytes.withNativePointer { pathPointer, pathSize ->
        passwordBytes.withNativePointer { passwordPointer, passwordSize ->
            open(pathPointer, pathSize, passwordPointer, passwordSize)
        }
    }
}

/** `null` for an unpaired surrogate, which the C side would reject anyway. */
private fun String.strictUtf8OrNull(): ByteArray? = try {
    encodeToByteArray(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}

private inline fun <T> ByteArray.withNativePointer(
    block: (CPointer<UByteVar>?, ULong) -> T,
): T = if (isEmpty()) {
    block(null, 0uL)
} else {
    usePinned { pinned ->
        block(
            pinned.addressOf(0).reinterpret(),
            size.convert(),
        )
    }
}

/**
 * Pins `[offset, offset + count)` for [block], or answers
 * [NATIVE_ZIP_BRIDGE_INVALID_ARGUMENT] when the range does not fit.
 */
private inline fun ByteArray.withNativeRange(
    offset: Int,
    count: Int,
    block: (CPointer<UByteVar>?, ULong) -> Long,
): Long {
    if (offset < 0 || count < 0 || offset > size - count) {
        return NATIVE_ZIP_BRIDGE_INVALID_ARGUMENT
    }
    return if (count == 0) {
        block(null, 0uL)
    } else {
        usePinned { pinned ->
            block(
                pinned.addressOf(offset).reinterpret(),
                count.convert(),
            )
        }
    }
}
