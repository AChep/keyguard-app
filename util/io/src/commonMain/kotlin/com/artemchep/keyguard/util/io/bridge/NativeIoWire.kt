package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.NativeErrorDiagnostic
import com.artemchep.keyguard.util.io.NativeErrorDomain

internal const val NATIVE_IO_ABI_VERSION: Int = 1

// Failure layout (bit 63 set): bits 0..7 operation, 8..15 kind, 16..23
// domain, 24..55 raw native error, bit 56 cleanup-incomplete, and 57..62
// reserved zero. The reserved bits
// make -1 unrepresentable as a failure, keeping it free as the end-of-file
// marker of read operations.
private const val FAILURE_KIND_SHIFT: Int = 8
private const val ERROR_DOMAIN_SHIFT: Int = 16
private const val RAW_CODE_SHIFT: Int = 24
private const val CLEANUP_INCOMPLETE_SHIFT: Int = 56
private const val RESERVED_SHIFT: Int = 57
private const val RESERVED_MASK: Long = 0x3fL
private const val BYTE_MASK: Long = 0xffL
private const val RAW_CODE_MASK: Long = 0xffffffffL
private const val NATIVE_IO_NO_FAILURE_KIND: Int = 0
private const val OPERATION_BRIDGE = 0
private const val OPERATION_BEGIN = 1
private const val OPERATION_CREATE_STAGED = 2
private const val OPERATION_WRITE = 3
private const val OPERATION_FLUSH_FILE = 4
private const val OPERATION_METADATA = 5
private const val OPERATION_RENAME = 6
private const val OPERATION_FLUSH_DIRECTORY = 7
private const val OPERATION_CLEANUP = 8
private const val OPERATION_READ = 9
private const val OPERATION_CLOSE = 10
private const val OPERATION_SWEEP = 11
private const val OPERATION_PREPARE_PARENT = 12
private const val OPERATION_FLUSH_PARENT = 13
private const val OPERATION_HARD_LINK = 14

// Wire codes of the reserved bridge invalid-argument failure.
private const val KIND_INVALID_INPUT = 8
private const val DOMAIN_BRIDGE = 3
private const val BRIDGE_ERROR_INVALID_ARGUMENT = 1

// Indexes are native ABI wire codes. Index zero is reserved for "no kind".
private val FAILURE_KINDS_BY_WIRE_CODE = listOf(
    FileSystemFailureKind.Other,
    FileSystemFailureKind.PermissionDenied,
    FileSystemFailureKind.ReadOnlyFilesystem,
    FileSystemFailureKind.NotFound,
    FileSystemFailureKind.AlreadyExists,
    FileSystemFailureKind.StorageFull,
    FileSystemFailureKind.QuotaExceeded,
    FileSystemFailureKind.ResourceBusy,
    FileSystemFailureKind.InvalidInput,
    FileSystemFailureKind.Interrupted,
    FileSystemFailureKind.Unsupported,
    FileSystemFailureKind.Other,
    FileSystemFailureKind.Internal,
    FileSystemFailureKind.DurabilityUnavailable,
)

// Indexes are native ABI wire codes. Index zero is handled as "no domain".
private val ERROR_DOMAINS_BY_WIRE_CODE = listOf(
    NativeErrorDomain.Unknown,
    NativeErrorDomain.PosixErrno,
    NativeErrorDomain.Win32LastError,
    NativeErrorDomain.Bridge,
)

/**
 * Protocol step reported by a native failure; used to render actionable
 * messages without the native layer disclosing paths.
 */
internal enum class NativeIoOperation(
    val wireCode: Int,
    val description: String,
) {
    Bridge(OPERATION_BRIDGE, "native bridge call"),
    Begin(OPERATION_BEGIN, "opening the destination directory"),
    CreateStaged(OPERATION_CREATE_STAGED, "creating the staged temporary file"),
    Write(OPERATION_WRITE, "writing to a native file"),
    FlushFile(OPERATION_FLUSH_FILE, "flushing the staged file to stable storage"),
    Metadata(OPERATION_METADATA, "preserving the destination permissions"),
    Rename(OPERATION_RENAME, "publishing the staged file"),
    FlushDir(OPERATION_FLUSH_DIRECTORY, "flushing the destination directory"),
    Cleanup(OPERATION_CLEANUP, "removing a temporary artifact"),
    Read(OPERATION_READ, "reading from a native file"),
    Close(OPERATION_CLOSE, "closing a native file"),
    Sweep(OPERATION_SWEEP, "sweeping temporary artifacts"),
    PrepareParent(OPERATION_PREPARE_PARENT, "resolving or creating the destination directory"),
    FlushParent(OPERATION_FLUSH_PARENT, "persisting the destination directory path"),
    HardLink(OPERATION_HARD_LINK, "publishing the staged file with a hard link"),
}

internal data class NativeIoFailure(
    val operation: NativeIoOperation,
    val failure: FileSystemFailure,
    val cleanupIncomplete: Boolean,
)

/**
 * The packed `TxnError(Bridge, InvalidInput, Bridge domain, code 1)` scalar.
 *
 * A bridge that rejects an argument before dispatching to the native ABI must
 * return this rather than throwing, so both bridges report an identical
 * scalar for identical input and the ordinary decode path produces the
 * platform-independent exception. The value is the ABI's
 * `pack_bridge_invalid_argument()`; [NativeIoWireTest] pins it against the
 * golden vector.
 */
internal const val NATIVE_IO_BRIDGE_INVALID_ARGUMENT: Long =
    (1L shl 63) or
        (BRIDGE_ERROR_INVALID_ARGUMENT.toLong() shl RAW_CODE_SHIFT) or
        (DOMAIN_BRIDGE.toLong() shl ERROR_DOMAIN_SHIFT) or
        (KIND_INVALID_INPUT.toLong() shl FAILURE_KIND_SHIFT) or
        OPERATION_BRIDGE.toLong()

internal fun isNativeIoFailure(packedResult: Long): Boolean =
    packedResult < 0L && packedResult != -1L

/**
 * Decodes a packed protocol failure.
 *
 * @throws IllegalArgumentException when the scalar violates the layout.
 */
internal fun decodeNativeIoFailure(packedResult: Long): NativeIoFailure {
    require(packedResult < 0L) {
        "Native IO result does not contain a failure"
    }
    require(((packedResult ushr RESERVED_SHIFT) and RESERVED_MASK) == 0L) {
        "Native IO returned non-zero reserved failure bits"
    }
    val operationCode = packedResult.byteAt(shift = 0)
    val operation = NativeIoOperation.entries.firstOrNull {
        it.wireCode == operationCode
    } ?: throw IllegalArgumentException(
        "Native IO returned unknown failure operation $operationCode",
    )
    val kind = requireNotNull(
        decodeNativeIoFailureKind(packedResult.byteAt(shift = FAILURE_KIND_SHIFT)),
    ) {
        "Native IO returned a failure without a failure kind"
    }
    val diagnostic = decodeNativeErrorDiagnostic(
        domainCode = packedResult.byteAt(shift = ERROR_DOMAIN_SHIFT),
        nativeErrorCode = ((packedResult ushr RAW_CODE_SHIFT) and RAW_CODE_MASK).toUInt(),
    )
    return NativeIoFailure(
        operation = operation,
        failure = FileSystemFailure(
            kind = kind,
            diagnostic = diagnostic,
        ),
        cleanupIncomplete = (
            (packedResult ushr CLEANUP_INCOMPLETE_SHIFT) and 1L
            ) != 0L,
    )
}

/**
 * Completes a native operation whose non-negative result is a success
 * payload and `-1` is end-of-file.
 *
 * @throws FileSystemOperationException for a structured native failure.
 */
internal fun completeNativeIoOperation(
    packedResult: Long,
    subject: String,
): Long {
    if (!isNativeIoFailure(packedResult)) return packedResult
    val decoded = try {
        decodeNativeIoFailure(packedResult)
    } catch (error: IllegalArgumentException) {
        throw invalidNativeIoResult(subject = subject, cause = error)
    }
    throw FileSystemOperationException(
        message = nativeIoFailureMessage(
            prefix = "Native IO failed while ${decoded.operation.description} for $subject",
            diagnostic = decoded.failure.diagnostic,
        ),
        failure = decoded.failure,
    )
}

internal fun decodeNativeIoFailureKind(
    wireCode: Int,
): FileSystemFailureKind? =
    if (wireCode == NATIVE_IO_NO_FAILURE_KIND) {
        null
    } else {
        FAILURE_KINDS_BY_WIRE_CODE.getOrNull(wireCode)
            ?: FileSystemFailureKind.Other
    }

internal fun decodeNativeErrorDiagnostic(
    domainCode: Int,
    nativeErrorCode: UInt,
): NativeErrorDiagnostic? {
    if (domainCode == 0) {
        require(nativeErrorCode == 0u) {
            "Native IO returned an error code without an error domain"
        }
        return null
    }
    val domain = ERROR_DOMAINS_BY_WIRE_CODE.getOrNull(domainCode)
        ?: NativeErrorDomain.Unknown
    return NativeErrorDiagnostic(
        domain = domain,
        code = nativeErrorCode,
    )
}

internal fun invalidNativeIoResult(
    subject: String,
    cause: Throwable? = null,
): FileSystemOperationException = FileSystemOperationException(
    message = "Native IO returned an invalid result for $subject",
    cause = cause,
    failure = FileSystemFailure(
        kind = FileSystemFailureKind.Internal,
    ),
)

internal fun nativeIoFailureMessage(
    prefix: String,
    diagnostic: NativeErrorDiagnostic?,
): String = if (diagnostic == null) {
    prefix
} else {
    "$prefix (native error domain=${diagnostic.domain}, code=${diagnostic.code})"
}

private fun Long.byteAt(
    shift: Int,
): Int = ((this ushr shift) and BYTE_MASK).toInt()
