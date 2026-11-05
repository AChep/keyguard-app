package com.artemchep.keyguard.util.io.bridge

import com.artemchep.keyguard.util.ffi.PackedFailure
import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.NativeErrorDiagnostic
import com.artemchep.keyguard.util.io.NativeErrorDomain

internal const val NATIVE_IO_ABI_VERSION: Int = 1

// Failures are `keyguard_ffi` packed scalars that assign reserved bit 56 to
// cleanup-incomplete; bits 57..62 stay zero. The reserved bits make -1
// unrepresentable as a failure, keeping it free as the end-of-file marker of
// read operations.
private const val CLEANUP_INCOMPLETE_BIT: Long = 1L shl 56
private const val RESERVED_BITS: Long = PackedFailure.RESERVED_BITS and CLEANUP_INCOMPLETE_BIT.inv()
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
internal const val NATIVE_IO_BRIDGE_INVALID_ARGUMENT: Long = PackedFailure.BRIDGE_INVALID_ARGUMENT

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
    val packed = requireNotNull(PackedFailure.decode(packedResult, RESERVED_BITS)) {
        "Native IO returned non-zero reserved failure bits"
    }
    val operation = NativeIoOperation.entries.firstOrNull {
        it.wireCode == packed.operation
    } ?: throw IllegalArgumentException(
        "Native IO returned unknown failure operation ${packed.operation}",
    )
    val kind = requireNotNull(decodeNativeIoFailureKind(packed.kind)) {
        "Native IO returned a failure without a failure kind"
    }
    val diagnostic = decodeNativeErrorDiagnostic(
        domainCode = packed.domain,
        nativeErrorCode = packed.rawCode.toUInt(),
    )
    return NativeIoFailure(
        operation = operation,
        failure = FileSystemFailure(
            kind = kind,
            diagnostic = diagnostic,
        ),
        cleanupIncomplete = (packedResult and CLEANUP_INCOMPLETE_BIT) != 0L,
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
