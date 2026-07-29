package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.NativeErrorDiagnostic
import kotlinx.io.IOException

open class AtomicFileWriteException(
    message: String,
    cause: Throwable? = null,
    val publicationState: AtomicPublicationState,
    val cleanupIncomplete: Boolean = false,
    failure: FileSystemFailure,
) : FileSystemOperationException(
    message = message,
    cause = cause,
    failure = failure,
)

class AtomicDestinationExistsException(
    message: String,
    cause: Throwable? = null,
    val cleanupFailure: FileSystemFailure? = null,
) : AtomicFileWriteException(
    message = message,
    cause = cause,
    publicationState = AtomicPublicationState.NotPublished,
    cleanupIncomplete = cleanupFailure != null,
    failure = FileSystemFailure(
        kind = FileSystemFailureKind.AlreadyExists,
    ),
)

class AtomicPublicationUnsupportedException(
    message: String,
    cause: Throwable? = null,
    diagnostic: NativeErrorDiagnostic? = null,
    cleanupIncomplete: Boolean = false,
) : AtomicFileWriteException(
    message = message,
    cause = cause,
    publicationState = AtomicPublicationState.NotPublished,
    cleanupIncomplete = cleanupIncomplete,
    failure = FileSystemFailure(
        kind = FileSystemFailureKind.Unsupported,
        diagnostic = diagnostic,
    ),
)

/**
 * A namespace mutation was issued, but its result could not be positively
 * reconciled with the retained staged-file identity.
 *
 * This exception carries neither a success receipt nor an achieved
 * synchronization level. Callers must not blindly retry, advance markers, or
 * delete source material.
 */
class AtomicPublicationUnknownException(
    message: String,
    cause: Throwable? = null,
    val publicationOperation: AtomicPublicationOperation,
    cleanupIncomplete: Boolean,
    failure: FileSystemFailure,
) : AtomicFileWriteException(
    message = message,
    cause = cause,
    publicationState = AtomicPublicationState.Unknown,
    cleanupIncomplete = cleanupIncomplete,
    failure = failure,
)

class AtomicSynchronizationException(
    message: String,
    cause: Throwable? = null,
    val achievedSyncLevel: AchievedSyncLevel,
    cleanupIncomplete: Boolean = false,
    failure: FileSystemFailure,
) : AtomicFileWriteException(
    message = message,
    cause = cause,
    publicationState = if (cleanupIncomplete) {
        AtomicPublicationState.PublishedSyncUnknownCleanupIncomplete
    } else {
        AtomicPublicationState.PublishedSyncUnknown
    },
    cleanupIncomplete = cleanupIncomplete,
    failure = failure,
)

/**
 * Publication succeeded, but temporary-artifact cleanup or native-handle
 * finalization did not complete. [cleanupFailure] is null when cleanup was
 * deliberately skipped to avoid deleting the published destination.
 */
class AtomicCleanupIncompleteException(
    message: String,
    cause: Throwable? = null,
    val achievedSyncLevel: AchievedSyncLevel,
    val cleanupFailure: FileSystemFailure?,
) : IOException(message, cause) {
    val publicationState: AtomicPublicationState = AtomicPublicationState.Published
    val cleanupIncomplete: Boolean = true
}
