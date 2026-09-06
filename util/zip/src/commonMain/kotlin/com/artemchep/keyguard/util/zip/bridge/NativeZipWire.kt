package com.artemchep.keyguard.util.zip.bridge

import com.artemchep.keyguard.util.zip.ZipException

internal const val NATIVE_ZIP_ABI_VERSION: Int = 1

internal const val NATIVE_ZIP_STATUS_SUCCESS: Long = 0L

// `keyguard_zip_operation`
internal const val NATIVE_ZIP_OP_BRIDGE: Int = 0
internal const val NATIVE_ZIP_OP_OPEN: Int = 1
internal const val NATIVE_ZIP_OP_BEGIN_ENTRY: Int = 2
internal const val NATIVE_ZIP_OP_WRITE: Int = 3
internal const val NATIVE_ZIP_OP_END_ENTRY: Int = 4
internal const val NATIVE_ZIP_OP_FINISH: Int = 5
internal const val NATIVE_ZIP_OP_ABORT: Int = 6
internal const val NATIVE_ZIP_OP_READER_OPEN: Int = 7
internal const val NATIVE_ZIP_OP_NEXT_ENTRY: Int = 8
internal const val NATIVE_ZIP_OP_READ: Int = 9
internal const val NATIVE_ZIP_OP_CLOSE: Int = 10

// `keyguard_zip_failure_kind`, numbered like `util/io`'s FailureKind.
internal const val NATIVE_ZIP_FAILURE_NONE: Int = 0
internal const val NATIVE_ZIP_FAILURE_PERMISSION_DENIED: Int = 1
internal const val NATIVE_ZIP_FAILURE_READ_ONLY_FILESYSTEM: Int = 2
internal const val NATIVE_ZIP_FAILURE_NOT_FOUND: Int = 3
internal const val NATIVE_ZIP_FAILURE_ALREADY_EXISTS: Int = 4
internal const val NATIVE_ZIP_FAILURE_STORAGE_FULL: Int = 5
internal const val NATIVE_ZIP_FAILURE_QUOTA_EXCEEDED: Int = 6
internal const val NATIVE_ZIP_FAILURE_RESOURCE_BUSY: Int = 7
internal const val NATIVE_ZIP_FAILURE_INVALID_INPUT: Int = 8
internal const val NATIVE_ZIP_FAILURE_INTERRUPTED: Int = 9
internal const val NATIVE_ZIP_FAILURE_UNSUPPORTED: Int = 10
internal const val NATIVE_ZIP_FAILURE_OTHER: Int = 11
internal const val NATIVE_ZIP_FAILURE_INTERNAL: Int = 12

// `keyguard_zip_error_domain`
internal const val NATIVE_ZIP_DOMAIN_NONE: Int = 0
internal const val NATIVE_ZIP_DOMAIN_POSIX_ERRNO: Int = 1
internal const val NATIVE_ZIP_DOMAIN_BRIDGE: Int = 3

// `keyguard_zip_bridge_error`, the raw code of the BRIDGE domain.
internal const val NATIVE_ZIP_BRIDGE_CODE_INVALID_ARGUMENT: Int = 1
internal const val NATIVE_ZIP_BRIDGE_CODE_PANIC: Int = 2
internal const val NATIVE_ZIP_BRIDGE_CODE_INTERNAL: Int = 3
internal const val NATIVE_ZIP_BRIDGE_CODE_INVALID_HANDLE: Int = 4
internal const val NATIVE_ZIP_BRIDGE_CODE_INVALID_STATE: Int = 5
internal const val NATIVE_ZIP_BRIDGE_CODE_NAME_TOO_LONG: Int = 6
internal const val NATIVE_ZIP_BRIDGE_CODE_ARCHIVE: Int = 7
internal const val NATIVE_ZIP_BRIDGE_CODE_WRONG_PASSWORD: Int = 8
internal const val NATIVE_ZIP_BRIDGE_CODE_UNSUPPORTED_ENTRY: Int = 9
internal const val NATIVE_ZIP_BRIDGE_CODE_BUFFER_TOO_SMALL: Int = 10

/**
 * Returned by `keyguard_zip_reader_next_entry` past the last entry. `-1` sets
 * every reserved bit, so it is unrepresentable as a failure.
 */
internal const val NATIVE_ZIP_END_OF_ARCHIVE: Long = -1L

internal const val NATIVE_ZIP_MAX_ENTRY_NAME_BYTES: Int = 4096

internal const val NATIVE_ZIP_MAX_PATH_BYTES: Int = 4096

// Failure layout (bit 63 set): bits 0..7 operation, 8..15 failure kind,
// 16..23 error domain, 24..55 raw code, 56..62 reserved zero. Same as `util/io`.
private const val FAILURE_KIND_SHIFT: Int = 8
private const val ERROR_DOMAIN_SHIFT: Int = 16
private const val RAW_CODE_SHIFT: Int = 24
private const val RESERVED_SHIFT: Int = 56
private const val RESERVED_MASK: Long = 0x7fL
private const val BYTE_MASK: Long = 0xffL
private const val UINT32_MASK: Long = 0xffffffffL

/**
 * The packed `INVALID_ARGUMENT` scalar. A bridge that rejects an argument
 * before dispatching returns this, so both sides of the boundary produce the
 * same exception.
 */
internal const val NATIVE_ZIP_BRIDGE_INVALID_ARGUMENT: Long =
    (1L shl 63) or
        (NATIVE_ZIP_BRIDGE_CODE_INVALID_ARGUMENT.toLong() shl RAW_CODE_SHIFT) or
        (NATIVE_ZIP_DOMAIN_BRIDGE.toLong() shl ERROR_DOMAIN_SHIFT) or
        (NATIVE_ZIP_FAILURE_INVALID_INPUT.toLong() shl FAILURE_KIND_SHIFT) or
        NATIVE_ZIP_OP_BRIDGE.toLong()

/** `keyguard_zip_failure_kind`, plus [Unknown] for kinds a future ABI adds. */
internal enum class NativeZipFailureKind {
    None,
    PermissionDenied,
    ReadOnlyFilesystem,
    NotFound,
    AlreadyExists,
    StorageFull,
    QuotaExceeded,
    ResourceBusy,
    InvalidInput,
    Interrupted,
    Unsupported,
    Other,
    Internal,
    Unknown,
}

/**
 * A decoded failure scalar. [rawCode] is an errno in the POSIX domain and a
 * `keyguard_zip_bridge_error` in the BRIDGE domain.
 */
internal data class NativeZipFailure(
    val operation: Int,
    val kind: NativeZipFailureKind,
    val domain: Int,
    val rawCode: Int,
)

internal fun isNativeZipFailure(packedResult: Long): Boolean = packedResult < 0L

/** @throws ZipException when the scalar violates the failure layout. */
internal fun decodeNativeZipFailure(packedResult: Long): NativeZipFailure {
    if (!isNativeZipFailure(packedResult)) {
        throw ZipException("Native zip result does not contain a failure")
    }
    if (((packedResult ushr RESERVED_SHIFT) and RESERVED_MASK) != 0L) {
        throw ZipException("Native zip returned non-zero reserved failure bits")
    }
    return NativeZipFailure(
        operation = packedResult.byteAt(shift = 0),
        kind = decodeNativeZipFailureKind(packedResult.byteAt(shift = FAILURE_KIND_SHIFT)),
        domain = packedResult.byteAt(shift = ERROR_DOMAIN_SHIFT),
        rawCode = ((packedResult ushr RAW_CODE_SHIFT) and UINT32_MASK).toInt(),
    )
}

/**
 * The message names only the operation and the reason; paths, entry names and
 * passwords never appear in it.
 */
internal fun nativeZipFailureException(packedResult: Long): ZipException =
    ZipException(nativeZipFailureMessage(decodeNativeZipFailure(packedResult)))

private fun nativeZipFailureMessage(failure: NativeZipFailure): String {
    val bridgeMessage = failure.bridgeMessageOrNull()
    if (bridgeMessage != null) {
        return "Native zip $bridgeMessage"
    }
    return "Native zip ${failure.operationPhrase()}: " +
        "${failure.kindPhrase()}${failure.codeSuffix()}"
}

/** The message of a bridge-domain failure, or `null` for any other domain. */
private fun NativeZipFailure.bridgeMessageOrNull(): String? {
    if (domain != NATIVE_ZIP_DOMAIN_BRIDGE) return null
    val reading = operation in NATIVE_ZIP_OP_READER_OPEN..NATIVE_ZIP_OP_CLOSE
    return when (rawCode) {
        NATIVE_ZIP_BRIDGE_CODE_PANIC -> if (reading) {
            "panicked while reading an archive"
        } else {
            "panicked while writing an archive"
        }

        NATIVE_ZIP_BRIDGE_CODE_ARCHIVE -> if (reading) {
            "could not read the archive"
        } else {
            "could not assemble the archive"
        }

        else -> NATIVE_ZIP_BRIDGE_MESSAGES[rawCode]
    }
}

private val NATIVE_ZIP_BRIDGE_MESSAGES: Map<Int, String> = mapOf(
    NATIVE_ZIP_BRIDGE_CODE_INVALID_ARGUMENT to "rejected an argument",
    NATIVE_ZIP_BRIDGE_CODE_INTERNAL to "failed internally",
    NATIVE_ZIP_BRIDGE_CODE_INVALID_HANDLE to "rejected an unknown archive handle",
    NATIVE_ZIP_BRIDGE_CODE_INVALID_STATE to "rejected an out of order operation",
    NATIVE_ZIP_BRIDGE_CODE_NAME_TOO_LONG to "rejected an entry name that is too long",
    NATIVE_ZIP_BRIDGE_CODE_WRONG_PASSWORD to "rejected the archive password",
    NATIVE_ZIP_BRIDGE_CODE_UNSUPPORTED_ENTRY to
        "cannot read an entry with an unsupported method",
    NATIVE_ZIP_BRIDGE_CODE_BUFFER_TOO_SMALL to
        "rejected a buffer too small for an entry name",
)

private fun NativeZipFailure.operationPhrase(): String = when (operation) {
    NATIVE_ZIP_OP_OPEN -> "failed to open the archive"
    NATIVE_ZIP_OP_BEGIN_ENTRY -> "failed to start an entry"
    NATIVE_ZIP_OP_WRITE -> "failed to write an entry"
    NATIVE_ZIP_OP_END_ENTRY -> "failed to close an entry"
    NATIVE_ZIP_OP_FINISH -> "failed to finish the archive"
    NATIVE_ZIP_OP_ABORT -> "failed to discard the archive"
    NATIVE_ZIP_OP_READER_OPEN -> "failed to open an archive for reading"
    NATIVE_ZIP_OP_NEXT_ENTRY -> "failed to advance to the next entry"
    NATIVE_ZIP_OP_READ -> "failed to read an entry"
    NATIVE_ZIP_OP_CLOSE -> "failed to close the archive"
    else -> "failed"
}

private fun NativeZipFailure.kindPhrase(): String = when (kind) {
    NativeZipFailureKind.PermissionDenied -> "permission denied"
    NativeZipFailureKind.ReadOnlyFilesystem -> "read-only filesystem"
    NativeZipFailureKind.NotFound -> "not found"
    NativeZipFailureKind.AlreadyExists -> "already exists"
    NativeZipFailureKind.StorageFull -> "no space left on the device"
    NativeZipFailureKind.QuotaExceeded -> "quota exceeded"
    NativeZipFailureKind.ResourceBusy -> "resource busy"
    NativeZipFailureKind.InvalidInput -> "invalid input"
    NativeZipFailureKind.Interrupted -> "interrupted"
    NativeZipFailureKind.Unsupported -> "unsupported operation"
    NativeZipFailureKind.Internal -> "internal error"
    NativeZipFailureKind.None,
    NativeZipFailureKind.Other,
    NativeZipFailureKind.Unknown,
    -> "unknown error"
}

private fun NativeZipFailure.codeSuffix(): String = when (domain) {
    NATIVE_ZIP_DOMAIN_POSIX_ERRNO -> " (errno $rawCode)"
    NATIVE_ZIP_DOMAIN_BRIDGE -> " (bridge code $rawCode)"
    else -> ""
}

private val NATIVE_ZIP_FAILURE_KINDS: Map<Int, NativeZipFailureKind> = mapOf(
    NATIVE_ZIP_FAILURE_NONE to NativeZipFailureKind.None,
    NATIVE_ZIP_FAILURE_PERMISSION_DENIED to NativeZipFailureKind.PermissionDenied,
    NATIVE_ZIP_FAILURE_READ_ONLY_FILESYSTEM to NativeZipFailureKind.ReadOnlyFilesystem,
    NATIVE_ZIP_FAILURE_NOT_FOUND to NativeZipFailureKind.NotFound,
    NATIVE_ZIP_FAILURE_ALREADY_EXISTS to NativeZipFailureKind.AlreadyExists,
    NATIVE_ZIP_FAILURE_STORAGE_FULL to NativeZipFailureKind.StorageFull,
    NATIVE_ZIP_FAILURE_QUOTA_EXCEEDED to NativeZipFailureKind.QuotaExceeded,
    NATIVE_ZIP_FAILURE_RESOURCE_BUSY to NativeZipFailureKind.ResourceBusy,
    NATIVE_ZIP_FAILURE_INVALID_INPUT to NativeZipFailureKind.InvalidInput,
    NATIVE_ZIP_FAILURE_INTERRUPTED to NativeZipFailureKind.Interrupted,
    NATIVE_ZIP_FAILURE_UNSUPPORTED to NativeZipFailureKind.Unsupported,
    NATIVE_ZIP_FAILURE_OTHER to NativeZipFailureKind.Other,
    NATIVE_ZIP_FAILURE_INTERNAL to NativeZipFailureKind.Internal,
)

private fun decodeNativeZipFailureKind(wireCode: Int): NativeZipFailureKind =
    NATIVE_ZIP_FAILURE_KINDS[wireCode] ?: NativeZipFailureKind.Unknown

private fun Long.byteAt(shift: Int): Int = ((this ushr shift) and BYTE_MASK).toInt()
