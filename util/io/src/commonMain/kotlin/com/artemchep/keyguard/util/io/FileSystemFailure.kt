package com.artemchep.keyguard.util.io

import kotlinx.io.IOException

/**
 * A portable classification of a filesystem failure.
 *
 * This describes what failed, not whether retrying the operation is appropriate.
 * Callers should apply retry policy in their own domain.
 */
enum class FileSystemFailureKind {
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
    DurabilityUnavailable,
}

/**
 * The namespace in which a native diagnostic error code is defined.
 */
enum class NativeErrorDomain {
    PosixErrno,
    Win32LastError,
    Bridge,
    Unknown,
}

/**
 * Platform-specific diagnostic metadata for a filesystem failure.
 *
 * [code] is intended for diagnostics only. Portable behavior must branch on
 * [FileSystemFailure.kind] instead.
 */
data class NativeErrorDiagnostic(
    val domain: NativeErrorDomain,
    val code: UInt,
)

/**
 * Portable failure semantics with optional platform diagnostic metadata.
 */
data class FileSystemFailure(
    val kind: FileSystemFailureKind,
    val diagnostic: NativeErrorDiagnostic? = null,
)

/**
 * Base exception for filesystem operations that expose portable failure semantics.
 */
open class FileSystemOperationException(
    message: String,
    cause: Throwable? = null,
    val failure: FileSystemFailure,
) : IOException(message, cause)
