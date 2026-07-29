package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.bridge.NativeIoOperation
import com.artemchep.keyguard.util.io.bridge.decodeNativeErrorDiagnostic
import com.artemchep.keyguard.util.io.bridge.decodeNativeIoFailureKind

// Commit-report layout (bit 63 clear): bits 0..3 outcome, 4..7 achieved tier
// (`0xf` means not established for publication-unknown only), 8..15 reported
// failure kind, 16..23 failure domain, 24..55 raw native error, and 56..62
// publication operation for publication-unknown only.
private const val COMMIT_OUTCOME_MASK: Long = 0x0fL
private const val COMMIT_ACHIEVED_SHIFT: Int = 4
private const val FAILURE_KIND_SHIFT: Int = 8
private const val ERROR_DOMAIN_SHIFT: Int = 16
private const val RAW_CODE_SHIFT: Int = 24
private const val PUBLICATION_OPERATION_SHIFT: Int = 56
private const val PUBLICATION_OPERATION_MASK: Long = 0x7fL
private const val COMMIT_ACHIEVED_NOT_ESTABLISHED: Int = 0x0f
private const val BYTE_MASK: Long = 0xffL
private const val RAW_CODE_MASK: Long = 0xffffffffL
private const val COMMIT_OUTCOME_PUBLISHED = 0
private const val COMMIT_OUTCOME_DESTINATION_EXISTS = 1
private const val COMMIT_OUTCOME_PUBLISHED_CLEANUP_INCOMPLETE = 2
private const val COMMIT_OUTCOME_PUBLISHED_SYNC_UNKNOWN = 3
private const val COMMIT_OUTCOME_PUBLISHED_SYNC_UNKNOWN_CLEANUP_INCOMPLETE = 4
private const val COMMIT_OUTCOME_DESTINATION_EXISTS_CLEANUP_INCOMPLETE = 5
private const val COMMIT_OUTCOME_PUBLICATION_UNKNOWN = 6
private const val COMMIT_OUTCOME_PUBLICATION_UNKNOWN_CLEANUP_INCOMPLETE = 7

internal enum class NativeIoCommitOutcome(val wireCode: Int) {
    Published(COMMIT_OUTCOME_PUBLISHED),
    DestinationExists(COMMIT_OUTCOME_DESTINATION_EXISTS),
    PublishedCleanupIncomplete(COMMIT_OUTCOME_PUBLISHED_CLEANUP_INCOMPLETE),
    PublishedSyncUnknown(COMMIT_OUTCOME_PUBLISHED_SYNC_UNKNOWN),
    PublishedSyncUnknownCleanupIncomplete(
        COMMIT_OUTCOME_PUBLISHED_SYNC_UNKNOWN_CLEANUP_INCOMPLETE,
    ),
    DestinationExistsCleanupIncomplete(COMMIT_OUTCOME_DESTINATION_EXISTS_CLEANUP_INCOMPLETE),
    PublicationUnknown(COMMIT_OUTCOME_PUBLICATION_UNKNOWN),
    PublicationUnknownCleanupIncomplete(COMMIT_OUTCOME_PUBLICATION_UNKNOWN_CLEANUP_INCOMPLETE),
}

internal data class NativeIoCommitReport(
    val outcome: NativeIoCommitOutcome,
    val achieved: AchievedSyncLevel?,
    val failure: FileSystemFailure?,
    val publicationOperation: NativeIoOperation?,
) {
    val cleanupIncomplete: Boolean
        get() = when (outcome) {
            NativeIoCommitOutcome.PublishedCleanupIncomplete,
            NativeIoCommitOutcome.PublishedSyncUnknownCleanupIncomplete,
            NativeIoCommitOutcome.DestinationExistsCleanupIncomplete,
            NativeIoCommitOutcome.PublicationUnknownCleanupIncomplete,
            -> true

            else -> false
        }
}

/**
 * Decodes a non-negative `txnCommit` payload.
 *
 * @throws IllegalArgumentException when the scalar violates the layout.
 */
internal fun decodeNativeIoCommitReport(packedResult: Long): NativeIoCommitReport {
    require(packedResult >= 0L) {
        "Native IO commit result contains a failure"
    }
    val diagnostic = decodeNativeErrorDiagnostic(
        domainCode = packedResult.byteAt(shift = ERROR_DOMAIN_SHIFT),
        nativeErrorCode = ((packedResult ushr RAW_CODE_SHIFT) and RAW_CODE_MASK).toUInt(),
    )
    val failure = decodeNativeIoFailureKind(
        packedResult.byteAt(shift = FAILURE_KIND_SHIFT),
    )?.let { kind ->
        FileSystemFailure(
            kind = kind,
            diagnostic = diagnostic,
        )
    }
    return NativeIoCommitReport(
        outcome = packedResult.decodeNativeIoCommitOutcome(),
        achieved = packedResult.decodeAchievedSyncLevel(),
        failure = failure,
        publicationOperation = packedResult.decodePublicationOperation(),
    ).also { report ->
        report.requireValid(diagnosticPresent = diagnostic != null)
    }
}

private fun Long.decodeNativeIoCommitOutcome(): NativeIoCommitOutcome {
    val outcomeCode = (this and COMMIT_OUTCOME_MASK).toInt()
    return NativeIoCommitOutcome.entries.firstOrNull { outcome ->
        outcome.wireCode == outcomeCode
    } ?: throw IllegalArgumentException(
        "Native IO returned unknown commit outcome $outcomeCode",
    )
}

private fun Long.decodeAchievedSyncLevel(): AchievedSyncLevel? {
    val achievedCode = ((this ushr COMMIT_ACHIEVED_SHIFT) and COMMIT_OUTCOME_MASK).toInt()
    return when (achievedCode) {
        0 -> AchievedSyncLevel.ProcessAtomic

        1 -> AchievedSyncLevel.FileSynchronized

        2 -> AchievedSyncLevel.FileAndNamespaceSynchronized

        COMMIT_ACHIEVED_NOT_ESTABLISHED -> null

        else -> throw IllegalArgumentException(
            "Native IO returned unknown achieved durability $achievedCode",
        )
    }
}

private fun Long.decodePublicationOperation(): NativeIoOperation? {
    val publicationOperationCode =
        ((this ushr PUBLICATION_OPERATION_SHIFT) and PUBLICATION_OPERATION_MASK).toInt()
    return when (publicationOperationCode) {
        0 -> null

        NativeIoOperation.Rename.wireCode -> NativeIoOperation.Rename

        NativeIoOperation.HardLink.wireCode -> NativeIoOperation.HardLink

        else -> throw IllegalArgumentException(
            "Native IO returned invalid publication operation $publicationOperationCode",
        )
    }
}

private fun NativeIoCommitReport.requireValid(diagnosticPresent: Boolean) {
    when (outcome) {
        NativeIoCommitOutcome.Published,
        NativeIoCommitOutcome.DestinationExists,
        -> requireCleanOutcome(diagnosticPresent)

        NativeIoCommitOutcome.PublishedCleanupIncomplete,
        -> requireCleanupIncompleteOutcome(diagnosticPresent)

        NativeIoCommitOutcome.PublishedSyncUnknown,
        NativeIoCommitOutcome.PublishedSyncUnknownCleanupIncomplete,
        NativeIoCommitOutcome.DestinationExistsCleanupIncomplete,
        -> requirePartialOutcome()

        NativeIoCommitOutcome.PublicationUnknown,
        NativeIoCommitOutcome.PublicationUnknownCleanupIncomplete,
        -> requirePublicationUnknownOutcome()
    }
}

private fun NativeIoCommitReport.requireCleanOutcome(diagnosticPresent: Boolean) {
    require(
        achieved != null &&
            failure == null &&
            !diagnosticPresent &&
            publicationOperation == null,
    ) {
        "Native IO returned failure metadata for a clean commit outcome"
    }
}

private fun NativeIoCommitReport.requireCleanupIncompleteOutcome(diagnosticPresent: Boolean) {
    require(
        achieved != null &&
            (failure != null || !diagnosticPresent) &&
            publicationOperation == null,
    ) {
        "Native IO returned an invalid cleanup-incomplete commit outcome"
    }
}

private fun NativeIoCommitReport.requirePartialOutcome() {
    require(
        achieved != null &&
            failure != null &&
            publicationOperation == null,
    ) {
        "Native IO returned an invalid partial commit outcome"
    }
}

private fun NativeIoCommitReport.requirePublicationUnknownOutcome() {
    require(
        achieved == null &&
            failure != null &&
            publicationOperation != null,
    ) {
        "Native IO returned an invalid publication-unknown outcome"
    }
}

private fun Long.byteAt(
    shift: Int,
): Int = ((this ushr shift) and BYTE_MASK).toInt()
