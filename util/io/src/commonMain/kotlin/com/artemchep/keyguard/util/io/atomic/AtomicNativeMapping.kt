package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.bridge.NativeIoOperation
import com.artemchep.keyguard.util.io.bridge.NativeIoTxnOptions
import com.artemchep.keyguard.util.io.bridge.decodeNativeIoFailure
import com.artemchep.keyguard.util.io.bridge.isNativeIoFailure
import com.artemchep.keyguard.util.io.bridge.nativeIoFailureMessage

internal val AtomicPublicationPolicy.nativeIoWireCode: Int
    get() = when (this) {
        is AtomicPublicationPolicy.Create -> 0

        is AtomicPublicationPolicy.Replace -> when (access) {
            is ReplacementAccessPolicy.UseRequestedPermissions -> 1
            is ReplacementAccessPolicy.PreserveExistingBasicPermissions -> 2
        }
    }

internal val AtomicPublicationPolicy.nativeIoPermissions: AtomicFilePermissions
    get() = when (this) {
        is AtomicPublicationPolicy.Create -> permissions

        is AtomicPublicationPolicy.Replace -> when (val policy = access) {
            is ReplacementAccessPolicy.UseRequestedPermissions -> policy.permissions

            is ReplacementAccessPolicy.PreserveExistingBasicPermissions ->
                policy.ifDestinationMissing
        }
    }

internal val AtomicFilePermissions.nativeIoWireCode: Int
    get() = when (this) {
        AtomicFilePermissions.OwnerOnly -> 0
        AtomicFilePermissions.ProcessDefault -> 1
    }

private val ParentDirectoryPolicy.nativeIoCreationWireCode: Int
    get() = when (this) {
        ParentDirectoryPolicy.RequireExisting -> 0
        is ParentDirectoryPolicy.CreateMissing -> 1
    }

private val ParentDirectoryPolicy.nativeIoPermissionsWireCode: Int
    get() = when (this) {
        ParentDirectoryPolicy.RequireExisting -> 0

        is ParentDirectoryPolicy.CreateMissing -> when (permissions) {
            AtomicDirectoryPermissions.OwnerOnly -> 0
            AtomicDirectoryPermissions.ProcessDefault -> 1
        }
    }

private val ExistingParentLinkPolicy.nativeIoWireCode: Int
    get() = when (this) {
        ExistingParentLinkPolicy.Reject -> 0
        ExistingParentLinkPolicy.FollowAndPin -> 1
    }

private val SyncLevel.nativeIoWireCode: Int
    get() = ordinal

internal fun nativeIoTxnOptions(
    options: AtomicWriteOptions,
): NativeIoTxnOptions {
    val preferred: SyncLevel
    val minimum: SyncLevel
    val mode: Int
    when (val synchronization = options.synchronization) {
        is SynchronizationPolicy.Required -> {
            preferred = synchronization.level
            minimum = synchronization.level
            mode = 0
        }

        is SynchronizationPolicy.Prefer -> {
            preferred = synchronization.preferred
            minimum = synchronization.minimum
            mode = 1
        }
    }
    return NativeIoTxnOptions(
        publication = options.publication.nativeIoWireCode,
        filePermissions = options.publication.nativeIoPermissions.nativeIoWireCode,
        parentCreation = options.parentDirectories.nativeIoCreationWireCode,
        directoryPermissions = options.parentDirectories.nativeIoPermissionsWireCode,
        existingParentLinks = options.existingParentLinks.nativeIoWireCode,
        preferredSyncLevel = preferred.nativeIoWireCode,
        minimumSyncLevel = minimum.nativeIoWireCode,
        syncPolicyMode = mode,
    )
}

/**
 * Maps a packed `txnBegin`/`txnWrite`/`txnCommit` failure to the atomic-write
 * exception hierarchy. Per the native contract, every packed transaction
 * failure means the destination was not changed; the staged temporary was
 * removed or remains eligible for the orphan sweeper.
 */
internal fun throwNativeIoTransactionFailure(
    packedResult: Long,
    destination: LocalPath,
): Nothing {
    val decoded = decodeNativeIoTransactionFailure(
        packedResult = packedResult,
        destination = destination,
    )
    val isPublicationOperation =
        decoded.operation == NativeIoOperation.Rename ||
            decoded.operation == NativeIoOperation.HardLink
    val failure = if (
        isPublicationOperation &&
        decoded.failure.kind == FileSystemFailureKind.Unsupported
    ) {
        AtomicPublicationUnsupportedException(
            message = "Atomic publication is unsupported for $destination",
            diagnostic = decoded.failure.diagnostic,
            cleanupIncomplete = decoded.cleanupIncomplete,
        )
    } else {
        AtomicFileWriteException(
            message = nativeIoFailureMessage(
                prefix = "Atomic write failed while ${decoded.operation.description} " +
                    "for $destination",
                diagnostic = decoded.failure.diagnostic,
            ),
            publicationState = AtomicPublicationState.NotPublished,
            cleanupIncomplete = decoded.cleanupIncomplete,
            failure = decoded.failure,
        )
    }
    throw failure
}

private fun decodeNativeIoTransactionFailure(
    packedResult: Long,
    destination: LocalPath,
) = try {
    decodeNativeIoFailure(packedResult)
} catch (error: IllegalArgumentException) {
    throw AtomicFileWriteException(
        message = "Native IO returned an invalid transaction result for $destination",
        cause = error,
        publicationState = AtomicPublicationState.Unknown,
        failure = FileSystemFailure(
            kind = FileSystemFailureKind.Internal,
        ),
    )
}

/**
 * Maps a `txnCommit` payload to a receipt, throwing for the
 * destination-exists outcome.
 */
internal fun completeNativeIoCommit(
    packedResult: Long,
    destination: LocalPath,
    requestedSynchronization: SynchronizationPolicy,
): AtomicWriteReceipt {
    if (isNativeIoFailure(packedResult)) {
        throwNativeIoTransactionFailure(
            packedResult = packedResult,
            destination = destination,
        )
    }
    val report = decodeNativeIoCommitReportOrThrow(
        packedResult = packedResult,
        destination = destination,
    )
    report.requireAcceptableSynchronization(
        requested = requestedSynchronization,
        destination = destination,
    )
    when (report.outcome) {
        NativeIoCommitOutcome.Published -> return AtomicWriteReceipt(
            requestedSynchronization = requestedSynchronization,
            achievedSyncLevel = requireNotNull(report.achieved),
        )

        NativeIoCommitOutcome.PublishedCleanupIncomplete -> return AtomicWriteReceipt(
            requestedSynchronization = requestedSynchronization,
            achievedSyncLevel = requireNotNull(report.achieved),
            cleanupFailure = report.failure,
            cleanupIncomplete = true,
        )

        else -> throw report.completionFailure(destination)
    }
}

private fun NativeIoCommitReport.completionFailure(
    destination: LocalPath,
): AtomicFileWriteException = when (outcome) {
    NativeIoCommitOutcome.DestinationExists -> AtomicDestinationExistsException(
        "Atomic create destination already exists: $destination",
    )

    NativeIoCommitOutcome.DestinationExistsCleanupIncomplete ->
        AtomicDestinationExistsException(
            message = "Atomic create destination already exists and staged cleanup was " +
                "incomplete: $destination",
            cleanupFailure = requireNotNull(failure),
        )

    NativeIoCommitOutcome.PublishedSyncUnknown -> AtomicSynchronizationException(
        message = "Atomic write was published but synchronization could not be established: " +
            destination,
        achievedSyncLevel = requireNotNull(achieved),
        failure = requireNotNull(failure),
    )

    NativeIoCommitOutcome.PublishedSyncUnknownCleanupIncomplete ->
        AtomicSynchronizationException(
            message = "Atomic write was published but synchronization could not be " +
                "established and cleanup was incomplete: " +
                destination,
            achievedSyncLevel = requireNotNull(achieved),
            cleanupIncomplete = true,
            failure = requireNotNull(failure),
        )

    NativeIoCommitOutcome.PublicationUnknown,
    NativeIoCommitOutcome.PublicationUnknownCleanupIncomplete,
    -> publicationUnknownException(destination)

    NativeIoCommitOutcome.Published,
    NativeIoCommitOutcome.PublishedCleanupIncomplete,
    -> error("Published outcomes must be completed with a receipt")
}

private fun decodeNativeIoCommitReportOrThrow(
    packedResult: Long,
    destination: LocalPath,
): NativeIoCommitReport = try {
    decodeNativeIoCommitReport(packedResult)
} catch (error: IllegalArgumentException) {
    throw AtomicFileWriteException(
        message = "Native IO returned an invalid commit result for $destination",
        cause = error,
        publicationState = AtomicPublicationState.Unknown,
        failure = FileSystemFailure(
            kind = FileSystemFailureKind.Internal,
        ),
    )
}

private fun NativeIoCommitReport.requireAcceptableSynchronization(
    requested: SynchronizationPolicy,
    destination: LocalPath,
) {
    val isPublished =
        outcome == NativeIoCommitOutcome.Published ||
            outcome == NativeIoCommitOutcome.PublishedCleanupIncomplete
    if (!isPublished) return
    val actual = requireNotNull(achieved)
    if (!requested.accepts(actual)) {
        throw AtomicSynchronizationException(
            message = "Atomic write was published with an unacceptable synchronization level: " +
                destination,
            achievedSyncLevel = actual,
            failure = FileSystemFailure(
                kind = FileSystemFailureKind.Internal,
            ),
        )
    }
}

private fun NativeIoCommitReport.publicationUnknownException(
    destination: LocalPath,
): AtomicPublicationUnknownException {
    val nativeOperation = requireNotNull(publicationOperation)
    val publicationOperation = when (nativeOperation) {
        NativeIoOperation.Rename -> AtomicPublicationOperation.Rename
        NativeIoOperation.HardLink -> AtomicPublicationOperation.HardLink
        else -> error("validated publication operation is not a publication primitive")
    }
    val failure = requireNotNull(failure)
    return AtomicPublicationUnknownException(
        message = nativeIoFailureMessage(
            prefix = "Atomic publication is unknown after " +
                "${nativeOperation.description} for $destination",
            diagnostic = failure.diagnostic,
        ),
        publicationOperation = publicationOperation,
        cleanupIncomplete = outcome ==
            NativeIoCommitOutcome.PublicationUnknownCleanupIncomplete,
        failure = failure,
    )
}

private fun SynchronizationPolicy.accepts(
    achieved: AchievedSyncLevel,
): Boolean = when (this) {
    is SynchronizationPolicy.Required -> achieved.ordinal == level.ordinal

    is SynchronizationPolicy.Prefer ->
        achieved.ordinal in minimum.ordinal..preferred.ordinal
}
