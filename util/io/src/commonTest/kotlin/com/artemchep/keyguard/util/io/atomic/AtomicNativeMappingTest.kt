package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.NativeErrorDiagnostic
import com.artemchep.keyguard.util.io.NativeErrorDomain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden wire vectors mirrored byte-identically by the Rust
 * `keyguard-io-core/src/abi.rs` test module; changing any value is an ABI
 * break.
 */
private object GoldenVectors {
    /** `TxnError(FlushFile, PermissionDenied, PosixErrno, EACCES=13)`. */
    val FLUSH_EACCES: Long = "800000000D010104".toULong(HEX_RADIX).toLong()

    /** `CommitReport(Published, FileAndNamespaceSynchronized)`. */
    const val COMMIT_PUBLISHED_NAMESPACE: Long = 0x20L

    /** `CommitReport(DestinationExists, FileSynchronized)`. */
    const val COMMIT_DESTINATION_EXISTS_FILE: Long = 0x11L

    /** PublicationUnknown(Rename, PermissionDenied), synchronization not established. */
    const val COMMIT_PUBLICATION_UNKNOWN_RENAME: Long = 0x06000000000001F6L

    /** PublicationUnknownCleanupIncomplete(HardLink, Other). */
    const val COMMIT_PUBLICATION_UNKNOWN_HARD_LINK: Long = 0x0E00000000000BF7L

    /** PublishedCleanupIncomplete, namespace synchronized, without a cleanup failure. */
    const val COMMIT_PUBLISHED_CLEANUP_SKIPPED: Long = 0x22L

    /** DestinationExistsCleanupIncomplete, file synchronized, cleanup PermissionDenied. */
    const val COMMIT_DESTINATION_EXISTS_CLEANUP_INCOMPLETE: Long = 0x115L

    /** PublishedSyncUnknown, file synchronized, secondary POSIX EIO failure. */
    const val COMMIT_PUBLISHED_SYNC_UNKNOWN: Long = 0x05010B13L
}

class AtomicNativeMappingTest {
    @Test
    fun transactionOptionsHaveStableV8WireLayout() {
        val options = AtomicWriteOptions(
            publication = AtomicPublicationPolicy.Replace(
                access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                    ifDestinationMissing = AtomicFilePermissions.ProcessDefault,
                ),
            ),
            parentDirectories = ParentDirectoryPolicy.CreateMissing(
                permissions = AtomicDirectoryPermissions.OwnerOnly,
            ),
            existingParentLinks = ExistingParentLinkPolicy.FollowAndPin,
            synchronization = SynchronizationPolicy.Prefer(
                preferred = SyncLevel.FileAndNamespaceSynchronized,
                minimum = SyncLevel.FileSynchronized,
            ),
        )

        assertContentEquals(
            intArrayOf(
                64, // size
                1, // version
                2, // preserve replacement permissions
                1, // process-default file fallback
                1, // create missing parents
                0, // owner-only created parents
                1, // follow and pin
                2, // preferred namespace synchronization
                1, // minimum file synchronization
                1, // prefer mode
                0, // flags
                0,
                0,
                0,
                0,
                0, // reserved
            ),
            nativeIoTxnOptions(options).toWireFields(),
        )
    }

    @Test
    fun publicationPoliciesHaveStableWireCodesAndPermissions() {
        val create = AtomicPublicationPolicy.Create(
            permissions = AtomicFilePermissions.OwnerOnly,
        )
        val replaceRequested = AtomicPublicationPolicy.Replace(
            access = ReplacementAccessPolicy.UseRequestedPermissions(
                permissions = AtomicFilePermissions.ProcessDefault,
            ),
        )
        val replacePreserved = AtomicPublicationPolicy.Replace(
            access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                ifDestinationMissing = AtomicFilePermissions.OwnerOnly,
            ),
        )

        assertEquals(0, create.nativeIoWireCode)
        assertEquals(AtomicFilePermissions.OwnerOnly, create.nativeIoPermissions)
        assertEquals(1, replaceRequested.nativeIoWireCode)
        assertEquals(
            AtomicFilePermissions.ProcessDefault,
            replaceRequested.nativeIoPermissions,
        )
        assertEquals(2, replacePreserved.nativeIoWireCode)
        assertEquals(
            AtomicFilePermissions.OwnerOnly,
            replacePreserved.nativeIoPermissions,
        )
    }

    @Test
    fun commitReportVectorsDecode() {
        val published = decodeNativeIoCommitReport(
            GoldenVectors.COMMIT_PUBLISHED_NAMESPACE,
        )
        assertEquals(NativeIoCommitOutcome.Published, published.outcome)
        assertEquals(
            AchievedSyncLevel.FileAndNamespaceSynchronized,
            published.achieved,
        )
        assertNull(published.failure)
        assertNull(published.publicationOperation)

        val exists = decodeNativeIoCommitReport(
            GoldenVectors.COMMIT_DESTINATION_EXISTS_FILE,
        )
        assertEquals(NativeIoCommitOutcome.DestinationExists, exists.outcome)
        assertEquals(AchievedSyncLevel.FileSynchronized, exists.achieved)
        assertNull(exists.failure)
        assertNull(exists.publicationOperation)
    }

    @Test
    fun partialCommitOutcomesCarryTheSecondaryFailure() {
        // PublishedSyncUnknown(3), achieved FileSynchronized(1),
        // Other kind(11=0x0b), PosixErrno domain(1), raw EIO(5).
        val packed = 3L or (1L shl 4) or (0x0bL shl 8) or (1L shl 16) or (5L shl 24)
        val decoded = decodeNativeIoCommitReport(packed)
        assertEquals(NativeIoCommitOutcome.PublishedSyncUnknown, decoded.outcome)
        assertEquals(AchievedSyncLevel.FileSynchronized, decoded.achieved)
        assertEquals(
            FileSystemFailure(
                kind = FileSystemFailureKind.Other,
                diagnostic = NativeErrorDiagnostic(
                    domain = NativeErrorDomain.PosixErrno,
                    code = 5u,
                ),
            ),
            decoded.failure,
        )
        assertNull(decoded.publicationOperation)
    }

    @Test
    fun publicationUnknownVectorsCarryNoAchievedTierAndRetainThePrimitive() {
        val rename = decodeNativeIoCommitReport(
            GoldenVectors.COMMIT_PUBLICATION_UNKNOWN_RENAME,
        )
        assertEquals(NativeIoCommitOutcome.PublicationUnknown, rename.outcome)
        assertNull(rename.achieved)
        assertEquals(FileSystemFailureKind.PermissionDenied, rename.failure?.kind)
        assertEquals(
            com.artemchep.keyguard.util.io.bridge.NativeIoOperation.Rename,
            rename.publicationOperation,
        )

        val hardLink = decodeNativeIoCommitReport(
            GoldenVectors.COMMIT_PUBLICATION_UNKNOWN_HARD_LINK,
        )
        assertEquals(
            NativeIoCommitOutcome.PublicationUnknownCleanupIncomplete,
            hardLink.outcome,
        )
        assertNull(hardLink.achieved)
        assertEquals(FileSystemFailureKind.Other, hardLink.failure?.kind)
        assertEquals(
            com.artemchep.keyguard.util.io.bridge.NativeIoOperation.HardLink,
            hardLink.publicationOperation,
        )
    }

    @Test
    fun synchronizationUnknownOutcomeWithoutAFailureIsRejected() {
        val packed = 3L or (2L shl 4)
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(packed)
        }
    }

    @Test
    fun cleanCommitOutcomeWithFailureMetadataIsRejected() {
        val packed = (2L shl 4) or (0x0bL shl 8)
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(packed)
        }
    }

    @Test
    fun unknownCommitOutcomeAndAchievedLevelAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(0x0fL)
        }
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(7L shl 4)
        }
    }

    @Test
    fun publicationUnknownRejectsEveryInvalidFieldCombination() {
        val valid = GoldenVectors.COMMIT_PUBLICATION_UNKNOWN_RENAME
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(valid and (0x0fL shl 4).inv())
        }
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(valid and (0xffL shl 8).inv())
        }
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(valid and (0x7fL shl 56).inv())
        }
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(
                (valid and (0x7fL shl 56).inv()) or (13L shl 56),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(
                GoldenVectors.COMMIT_PUBLISHED_NAMESPACE or (6L shl 56),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            decodeNativeIoCommitReport(0xF0L)
        }
    }
}

class AtomicNativeCompletionTest {
    @Test
    fun commitCompletionMapsOutcomesToReceiptsAndExceptions() {
        val destination = LocalPath("/vault/store.kdbx")
        val requiredNamespace = SynchronizationPolicy.Required(
            SyncLevel.FileAndNamespaceSynchronized,
        )

        assertPublishedCommit(destination, requiredNamespace)
        assertCleanupSkippedCommit(destination, requiredNamespace)
        assertDestinationExistsCommits(destination)
        assertSynchronizationUnknownCommit(destination, requiredNamespace)
        assertPublicationUnknownCommits(destination, requiredNamespace)
    }

    @Test
    fun preferAcceptsAPlatformCapabilityDowngradeWithinItsRange() {
        val receipt = completeNativeIoCommit(
            packedResult = 1L shl 4,
            destination = LocalPath("/vault/store.kdbx"),
            requestedSynchronization = SynchronizationPolicy.Prefer(
                preferred = SyncLevel.FileAndNamespaceSynchronized,
                minimum = SyncLevel.FileSynchronized,
            ),
        )

        assertEquals(AchievedSyncLevel.FileSynchronized, receipt.achievedSyncLevel)
        assertTrue(receipt.capabilityDowngraded)
    }

    @Test
    fun combinedSynchronizationAndCleanupFailureRetainsBothStates() {
        val error = assertFailsWith<AtomicSynchronizationException> {
            completeNativeIoCommit(
                packedResult =
                4L or (1L shl 4) or (13L shl 8),
                destination = LocalPath("/vault/store.kdbx"),
                requestedSynchronization = SynchronizationPolicy.Prefer(
                    preferred = SyncLevel.FileAndNamespaceSynchronized,
                    minimum = SyncLevel.FileSynchronized,
                ),
            )
        }

        assertEquals(
            AtomicPublicationState.PublishedSyncUnknownCleanupIncomplete,
            error.publicationState,
        )
        assertTrue(error.cleanupIncomplete)
        assertEquals(
            FileSystemFailureKind.DurabilityUnavailable,
            error.failure.kind,
        )
    }

    @Test
    fun requiredSynchronizationCannotSilentlyReportAWeakerLevel() {
        val error = assertFailsWith<AtomicSynchronizationException> {
            completeNativeIoCommit(
                packedResult = 1L shl 4,
                destination = LocalPath("/vault/store.kdbx"),
                requestedSynchronization = SynchronizationPolicy.Required(
                    SyncLevel.FileAndNamespaceSynchronized,
                ),
            )
        }

        assertEquals(AchievedSyncLevel.FileSynchronized, error.achievedSyncLevel)
        assertEquals(FileSystemFailureKind.Internal, error.failure.kind)
    }

    @Test
    fun transactionFailuresMapToTheExceptionHierarchy() {
        val destination = LocalPath("/vault/store.kdbx")

        val generic = assertFailsWith<AtomicFileWriteException> {
            throwNativeIoTransactionFailure(
                packedResult = GoldenVectors.FLUSH_EACCES,
                destination = destination,
            )
        }
        assertEquals(AtomicPublicationState.NotPublished, generic.publicationState)
        assertEquals(FileSystemFailureKind.PermissionDenied, generic.failure.kind)
        assertEquals(false, generic.cleanupIncomplete)

        val cleanupIncomplete = assertFailsWith<AtomicFileWriteException> {
            throwNativeIoTransactionFailure(
                packedResult = GoldenVectors.FLUSH_EACCES or (1L shl 56),
                destination = destination,
            )
        }
        assertEquals(AtomicPublicationState.NotPublished, cleanupIncomplete.publicationState)
        assertEquals(FileSystemFailureKind.PermissionDenied, cleanupIncomplete.failure.kind)
        assertTrue(cleanupIncomplete.cleanupIncomplete)

        // Rename + Unsupported maps to the dedicated unsupported exception.
        val unsupportedPacked =
            (1UL shl 63) or 6UL or (10UL shl 8) or (1UL shl 16) or (95UL shl 24)
        assertFailsWith<AtomicPublicationUnsupportedException> {
            throwNativeIoTransactionFailure(
                packedResult = unsupportedPacked.toLong(),
                destination = destination,
            )
        }

        // A malformed scalar must not be trusted: publication state Unknown.
        val malformed = assertFailsWith<AtomicFileWriteException> {
            throwNativeIoTransactionFailure(
                packedResult = -1L,
                destination = destination,
            )
        }
        assertEquals(AtomicPublicationState.Unknown, malformed.publicationState)
        assertEquals(FileSystemFailureKind.Internal, malformed.failure.kind)
    }

    @Test
    fun cleanupEnforcementThrowsWithoutLosingPublishedState() {
        val receipt = AtomicWriteReceipt(
            requestedSynchronization = SynchronizationPolicy.Required(
                SyncLevel.FileSynchronized,
            ),
            achievedSyncLevel = AchievedSyncLevel.FileSynchronized,
            cleanupFailure = FileSystemFailure(
                kind = FileSystemFailureKind.PermissionDenied,
            ),
        )

        val error = assertFailsWith<AtomicCleanupIncompleteException> {
            receipt.requireCleanupComplete()
        }

        assertEquals(AtomicPublicationState.Published, error.publicationState)
        assertEquals(AchievedSyncLevel.FileSynchronized, error.achievedSyncLevel)
        assertEquals(FileSystemFailureKind.PermissionDenied, error.cleanupFailure?.kind)
        assertTrue(error.cleanupIncomplete)
    }

    @Test
    fun cleanupEnforcementRepresentsDeliberatelySkippedCleanupWithoutInventingANativeFailure() {
        val receipt = AtomicWriteReceipt(
            requestedSynchronization = SynchronizationPolicy.Required(
                SyncLevel.FileSynchronized,
            ),
            achievedSyncLevel = AchievedSyncLevel.FileSynchronized,
            cleanupIncomplete = true,
        )

        val error = assertFailsWith<AtomicCleanupIncompleteException> {
            receipt.requireCleanupComplete()
        }

        assertNull(receipt.cleanupFailure)
        assertNull(error.cleanupFailure)
        assertEquals(AtomicPublicationState.Published, error.publicationState)
        assertTrue(error.cleanupIncomplete)
    }
}

private fun assertPublishedCommit(
    destination: LocalPath,
    requiredNamespace: SynchronizationPolicy.Required,
) {
    val published = completeNativeIoCommit(
        packedResult = GoldenVectors.COMMIT_PUBLISHED_NAMESPACE,
        destination = destination,
        requestedSynchronization = requiredNamespace,
    )

    assertEquals(
        AchievedSyncLevel.FileAndNamespaceSynchronized,
        published.achievedSyncLevel,
    )
    assertNull(published.cleanupFailure)
    assertEquals(AtomicPublicationState.Published, published.publicationState)
    assertEquals(false, published.cleanupIncomplete)
}

private fun assertCleanupSkippedCommit(
    destination: LocalPath,
    requiredNamespace: SynchronizationPolicy.Required,
) {
    val cleanupSkipped = completeNativeIoCommit(
        packedResult = GoldenVectors.COMMIT_PUBLISHED_CLEANUP_SKIPPED,
        destination = destination,
        requestedSynchronization = requiredNamespace,
    )

    assertTrue(cleanupSkipped.cleanupIncomplete)
    assertNull(cleanupSkipped.cleanupFailure)
}

private fun assertDestinationExistsCommits(destination: LocalPath) {
    val requiredFileSynchronization = SynchronizationPolicy.Required(
        SyncLevel.FileSynchronized,
    )
    assertFailsWith<AtomicDestinationExistsException> {
        completeNativeIoCommit(
            packedResult = GoldenVectors.COMMIT_DESTINATION_EXISTS_FILE,
            destination = destination,
            requestedSynchronization = requiredFileSynchronization,
        )
    }
    val existsCleanupIncomplete = assertFailsWith<AtomicDestinationExistsException> {
        completeNativeIoCommit(
            packedResult = GoldenVectors.COMMIT_DESTINATION_EXISTS_CLEANUP_INCOMPLETE,
            destination = destination,
            requestedSynchronization = requiredFileSynchronization,
        )
    }

    assertEquals(AtomicPublicationState.NotPublished, existsCleanupIncomplete.publicationState)
    assertTrue(existsCleanupIncomplete.cleanupIncomplete)
    assertEquals(
        FileSystemFailureKind.PermissionDenied,
        existsCleanupIncomplete.cleanupFailure?.kind,
    )
}

private fun assertSynchronizationUnknownCommit(
    destination: LocalPath,
    requiredNamespace: SynchronizationPolicy.Required,
) {
    val synchronizationUnknown = assertFailsWith<AtomicSynchronizationException> {
        completeNativeIoCommit(
            packedResult = GoldenVectors.COMMIT_PUBLISHED_SYNC_UNKNOWN,
            destination = destination,
            requestedSynchronization = requiredNamespace,
        )
    }

    assertEquals(
        AtomicPublicationState.PublishedSyncUnknown,
        synchronizationUnknown.publicationState,
    )
    assertEquals(
        AchievedSyncLevel.FileSynchronized,
        synchronizationUnknown.achievedSyncLevel,
    )
    assertEquals(FileSystemFailureKind.Other, synchronizationUnknown.failure.kind)
}

private fun assertPublicationUnknownCommits(
    destination: LocalPath,
    requiredNamespace: SynchronizationPolicy.Required,
) {
    val publicationUnknown = assertFailsWith<AtomicPublicationUnknownException> {
        completeNativeIoCommit(
            packedResult = GoldenVectors.COMMIT_PUBLICATION_UNKNOWN_RENAME,
            destination = destination,
            requestedSynchronization = requiredNamespace,
        )
    }
    assertEquals(AtomicPublicationState.Unknown, publicationUnknown.publicationState)
    assertEquals(AtomicPublicationOperation.Rename, publicationUnknown.publicationOperation)
    assertEquals(FileSystemFailureKind.PermissionDenied, publicationUnknown.failure.kind)
    assertEquals(false, publicationUnknown.cleanupIncomplete)

    val unknownCleanupIncomplete = assertFailsWith<AtomicPublicationUnknownException> {
        completeNativeIoCommit(
            packedResult = GoldenVectors.COMMIT_PUBLICATION_UNKNOWN_HARD_LINK,
            destination = destination,
            requestedSynchronization = requiredNamespace,
        )
    }
    assertEquals(AtomicPublicationState.Unknown, unknownCleanupIncomplete.publicationState)
    assertEquals(
        AtomicPublicationOperation.HardLink,
        unknownCleanupIncomplete.publicationOperation,
    )
    assertTrue(unknownCleanupIncomplete.cleanupIncomplete)
}

private const val HEX_RADIX = 16
