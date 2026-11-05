@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.ffi.StaticLibrary
import com.artemchep.keyguard.util.ffi.withNativePointer
import com.artemchep.keyguard.util.ffi.withStrictUtf8
import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.ffi.keyguard_io_abi_version
import com.artemchep.keyguard.util.io.ffi.keyguard_io_directory_close
import com.artemchep.keyguard.util.io.ffi.keyguard_io_directory_open
import com.artemchep.keyguard.util.io.ffi.keyguard_io_scratch_close
import com.artemchep.keyguard.util.io.ffi.keyguard_io_scratch_length
import com.artemchep.keyguard.util.io.ffi.keyguard_io_scratch_open
import com.artemchep.keyguard.util.io.ffi.keyguard_io_scratch_read_at
import com.artemchep.keyguard.util.io.ffi.keyguard_io_scratch_seal
import com.artemchep.keyguard.util.io.ffi.keyguard_io_scratch_write
import com.artemchep.keyguard.util.io.ffi.keyguard_io_sweep_orphans
import com.artemchep.keyguard.util.io.ffi.keyguard_io_sweep_report_v1
import com.artemchep.keyguard.util.io.ffi.keyguard_io_txn_abort
import com.artemchep.keyguard.util.io.ffi.keyguard_io_txn_begin
import com.artemchep.keyguard.util.io.ffi.keyguard_io_txn_begin_at_directory
import com.artemchep.keyguard.util.io.ffi.keyguard_io_txn_commit
import com.artemchep.keyguard.util.io.ffi.keyguard_io_txn_options_v1
import com.artemchep.keyguard.util.io.ffi.keyguard_io_txn_write
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf

// The cohesive actual object mirrors the fixed native ABI surface; splitting it would obscure
// expect/actual parity and move ABI calls away from the compatibility checks guarding them.
@Suppress("TooManyFunctions")
internal actual object NativeIo {
    private val library = StaticLibrary {
        val actual = keyguard_io_abi_version().toInt()
        if (actual != NATIVE_IO_ABI_VERSION) {
            throw FileSystemOperationException(
                message = "Unsupported native IO ABI $actual; expected $NATIVE_IO_ABI_VERSION",
                failure = FileSystemFailure(
                    kind = FileSystemFailureKind.Internal,
                ),
            )
        }
    }

    actual fun directoryOpen(directory: String): Long {
        ensureCompatibleAbi()
        return directory.withStrictUtf8(NATIVE_IO_BRIDGE_INVALID_ARGUMENT) { pointer, size ->
            keyguard_io_directory_open(pointer, size)
        }
    }

    actual fun directoryClose(handle: Long): Long {
        ensureCompatibleAbi()
        if (isNegativeHandle(handle)) return NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        return keyguard_io_directory_close(handle.toULong())
    }

    actual fun txnBegin(
        destination: String,
        options: NativeIoTxnOptions,
    ): Long {
        ensureCompatibleAbi()
        return memScoped {
            val wire = alloc<keyguard_io_txn_options_v1>()
            wire.size = sizeOf<keyguard_io_txn_options_v1>().toUInt()
            wire.version = NativeIoTxnOptions.TXN_OPTIONS_VERSION.toUInt()
            wire.publication = options.publication
            wire.file_permissions = options.filePermissions
            wire.parent_creation = options.parentCreation
            wire.directory_permissions = options.directoryPermissions
            wire.existing_parent_links = options.existingParentLinks
            wire.preferred_sync_level = options.preferredSyncLevel
            wire.minimum_sync_level = options.minimumSyncLevel
            wire.sync_policy_mode = options.syncPolicyMode
            wire.flags = options.flags.toUInt()
            wire.reserved[0] = options.reserved0.toUInt()
            wire.reserved[1] = options.reserved1.toUInt()
            wire.reserved[2] = options.reserved2.toUInt()
            wire.reserved[TXN_RESERVED_3_INDEX] = options.reserved3.toUInt()
            wire.reserved[TXN_RESERVED_4_INDEX] = options.reserved4.toUInt()
            destination.withStrictUtf8(NATIVE_IO_BRIDGE_INVALID_ARGUMENT) { pointer, size ->
                keyguard_io_txn_begin(
                    pointer,
                    size,
                    wire.ptr,
                )
            }
        }
    }

    actual fun txnWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        ensureCompatibleAbi()
        return if (isNegativeHandle(handle)) {
            NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        } else {
            checkArrayRange(input.size, offset, length)
            input.withNativePointer(offset, length) { pointer, size ->
                keyguard_io_txn_write(handle.toULong(), pointer, size)
            }
        }
    }

    actual fun txnBeginAtDirectory(
        directoryHandle: Long,
        relativeDestination: String,
        options: NativeIoTxnOptions,
    ): Long {
        ensureCompatibleAbi()
        if (isNegativeHandle(directoryHandle)) return NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        return memScoped {
            val wire = alloc<keyguard_io_txn_options_v1>()
            wire.size = sizeOf<keyguard_io_txn_options_v1>().toUInt()
            wire.version = NativeIoTxnOptions.TXN_OPTIONS_VERSION.toUInt()
            wire.publication = options.publication
            wire.file_permissions = options.filePermissions
            wire.parent_creation = options.parentCreation
            wire.directory_permissions = options.directoryPermissions
            wire.existing_parent_links = options.existingParentLinks
            wire.preferred_sync_level = options.preferredSyncLevel
            wire.minimum_sync_level = options.minimumSyncLevel
            wire.sync_policy_mode = options.syncPolicyMode
            wire.flags = options.flags.toUInt()
            wire.reserved[0] = options.reserved0.toUInt()
            wire.reserved[1] = options.reserved1.toUInt()
            wire.reserved[2] = options.reserved2.toUInt()
            wire.reserved[TXN_RESERVED_3_INDEX] = options.reserved3.toUInt()
            wire.reserved[TXN_RESERVED_4_INDEX] = options.reserved4.toUInt()
            relativeDestination.withStrictUtf8(NATIVE_IO_BRIDGE_INVALID_ARGUMENT) { pointer, size ->
                keyguard_io_txn_begin_at_directory(
                    directoryHandle.toULong(),
                    pointer,
                    size,
                    wire.ptr,
                )
            }
        }
    }

    actual fun txnCommit(handle: Long): Long {
        ensureCompatibleAbi()
        if (isNegativeHandle(handle)) return NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        return keyguard_io_txn_commit(handle.toULong())
    }

    actual fun txnAbort(handle: Long): Long {
        ensureCompatibleAbi()
        if (isNegativeHandle(handle)) return NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        return keyguard_io_txn_abort(handle.toULong())
    }

    actual fun scratchOpen(directory: String): Long {
        ensureCompatibleAbi()
        return directory.withStrictUtf8(NATIVE_IO_BRIDGE_INVALID_ARGUMENT) { pointer, size ->
            keyguard_io_scratch_open(pointer, size)
        }
    }

    actual fun scratchWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        ensureCompatibleAbi()
        return if (isNegativeHandle(handle)) {
            NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        } else {
            checkArrayRange(input.size, offset, length)
            input.withNativePointer(offset, length) { pointer, size ->
                keyguard_io_scratch_write(handle.toULong(), pointer, size)
            }
        }
    }

    actual fun scratchSeal(handle: Long): Long {
        ensureCompatibleAbi()
        if (isNegativeHandle(handle)) return NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        return keyguard_io_scratch_seal(handle.toULong())
    }

    actual fun scratchLength(handle: Long): Long {
        ensureCompatibleAbi()
        if (isNegativeHandle(handle)) return NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        return keyguard_io_scratch_length(handle.toULong())
    }

    actual fun scratchReadAt(
        handle: Long,
        position: Long,
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        ensureCompatibleAbi()
        return if (isNegativeHandle(handle) || position < 0L) {
            // The ABI takes unsigned handles and offsets, so negative values
            // would wrap. Match the JNI bridge by rejecting them up front.
            NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        } else {
            checkArrayRange(output.size, offset, length)
            output.withNativePointer(offset, length) { pointer, size ->
                keyguard_io_scratch_read_at(handle.toULong(), position.toULong(), pointer, size)
            }
        }
    }

    actual fun scratchClose(handle: Long): Long {
        ensureCompatibleAbi()
        if (isNegativeHandle(handle)) return NATIVE_IO_BRIDGE_INVALID_ARGUMENT
        return keyguard_io_scratch_close(handle.toULong())
    }

    actual fun sweepOrphans(
        directory: String,
        olderThanMs: Long,
        roleMask: Int,
    ): LongArray {
        ensureCompatibleAbi()
        // Coercing a negative age to zero would sweep every artifact
        // regardless of age, quietly disabling the guard that keeps a live
        // transaction's staged file from being reclaimed. The JNI bridge
        // rejects it; a caller that means "sweep everything" must say zero.
        if (olderThanMs < 0L) {
            return longArrayOf(NATIVE_IO_BRIDGE_INVALID_ARGUMENT)
        }
        return memScoped {
            val report = alloc<keyguard_io_sweep_report_v1>()
            report.size = sizeOf<keyguard_io_sweep_report_v1>().toUInt()
            val result = directory.withStrictUtf8(NATIVE_IO_BRIDGE_INVALID_ARGUMENT) { pointer, size ->
                keyguard_io_sweep_orphans(
                    pointer,
                    size,
                    olderThanMs.toULong(),
                    roleMask.toUInt(),
                    report.ptr,
                )
            }
            if (result < 0L) {
                longArrayOf(result)
            } else {
                longArrayOf(
                    report.size.toLong(),
                    report.version.toLong(),
                    report.status.toLong(),
                    report.first_failure_kind.toLong(),
                    report.first_failure_domain.toLong(),
                    report.first_failure_raw_code.toLong(),
                    report.entries_seen.toLong(),
                    report.candidate_names.toLong(),
                    report.removed.toLong(),
                    report.skipped_young.toLong(),
                    report.skipped_busy.toLong(),
                    report.skipped_unsafe.toLong(),
                    report.skipped_changed.toLong(),
                    report.inspection_failed.toLong(),
                    report.removal_failed.toLong(),
                )
            }
        }
    }

    /**
     * Whether [handle] cannot be a native handle.
     *
     * The ABI takes an unsigned handle, so a negative [Long] would wrap to a
     * huge id and come back as an unknown handle — `NotFound` — instead of an
     * invalid argument. The JNI bridge rejects it up front with `u64::try_from`;
     * matching that keeps failure classification identical across platforms,
     * exactly as this file already does for a negative read position and a
     * negative sweep age.
     */
    private fun isNegativeHandle(handle: Long): Boolean = handle < 0L

    private fun ensureCompatibleAbi() = library.ensureVerified()

    /**
     * Validates a caller-selected `[offset, offset + length)` window before it
     * reaches the C ABI.
     *
     * The C ABI trusts the window: `bytes_from_raw` and `bytes_from_raw_mut`
     * reject just null and lengths above `isize::MAX`, so an unvalidated
     * window reads — or, through `scratchReadAt`, writes — past the end of a
     * pinned [ByteArray]. This check runs first, so a bad window fails as a
     * [FileSystemOperationException]; the range check of `withNativePointer`
     * is only a backstop.
     *
     * The comparison is written as `length > arraySize - offset` rather than
     * `offset + length > arraySize` because the latter overflows: with
     * `offset = 2` and `length = Int.MAX_VALUE - 1` the sum wraps to
     * `Int.MIN_VALUE`, which is not greater than any array size, so the check
     * passes and roughly two gigabytes beyond the array become addressable.
     * Once `offset` and `length` are known non-negative, subtracting from a
     * non-negative size cannot leave the `Int` range.
     */
    private fun checkArrayRange(arraySize: Int, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || length > arraySize - offset) {
            throw FileSystemOperationException(
                message = "Invalid native IO array range",
                failure = FileSystemFailure(
                    kind = FileSystemFailureKind.InvalidInput,
                ),
            )
        }
    }
}

private const val TXN_RESERVED_3_INDEX = 3
private const val TXN_RESERVED_4_INDEX = 4
