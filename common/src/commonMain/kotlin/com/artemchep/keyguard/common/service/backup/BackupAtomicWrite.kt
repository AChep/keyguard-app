package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.atomic.AtomicDestinationExistsException
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationUnknownException
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationUnsupportedException
import com.artemchep.keyguard.util.io.atomic.AtomicSynchronizationException
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy

/** Options for publishing a backup object into a local folder. */
internal fun BackupWriteMode.toBackupAtomicWriteOptions(): AtomicWriteOptions = AtomicWriteOptions(
    publication = when (this) {
        BackupWriteMode.Create -> AtomicPublicationPolicy.Create(
            permissions = AtomicFilePermissions.ProcessDefault,
        )

        BackupWriteMode.CreateOrReplace -> AtomicPublicationPolicy.Replace(
            access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                ifDestinationMissing = AtomicFilePermissions.ProcessDefault,
            ),
        )
    },
    parentDirectories = ParentDirectoryPolicy.CreateMissing(
        permissions = AtomicDirectoryPermissions.ProcessDefault,
    ),
    existingParentLinks = ExistingParentLinkPolicy.Reject,
    synchronization = SynchronizationPolicy.Prefer(
        preferred = SyncLevel.FileAndNamespaceSynchronized,
        minimum = SyncLevel.FileSynchronized,
    ),
)

/**
 * Translates an atomic write failure into the store's vocabulary, or returns
 * `null` when [cause] did not come from the atomic write API.
 */
internal fun atomicWriteFailureOrNull(
    key: BackupObjectKey,
    cause: Exception,
): Exception? = when (cause) {
    is AtomicDestinationExistsException -> BackupObjectStoreException.AlreadyExists(
        key = key,
        cause = cause,
    )

    is AtomicPublicationUnknownException -> BackupObjectStoreException.PublicationUnknown(
        key = key,
        cause = cause,
    )

    is AtomicSynchronizationException -> BackupObjectStoreException.PublishedSynchronizationUnknown(
        key = key,
        achievedSyncLevel = cause.achievedSyncLevel,
        cleanupIncomplete = cause.cleanupIncomplete,
        cause = cause,
    )

    is AtomicPublicationUnsupportedException -> BackupObjectStoreException.AtomicWriteUnsupported(
        key = key,
        cause = cause,
    )

    is FileSystemOperationException -> when (cause.failure.kind) {
        FileSystemFailureKind.PermissionDenied,
        FileSystemFailureKind.ReadOnlyFilesystem,
        -> BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Write,
            key = key,
            cause = cause,
        )

        FileSystemFailureKind.Unsupported -> BackupObjectStoreException.AtomicWriteUnsupported(
            key = key,
            cause = cause,
        )

        FileSystemFailureKind.InvalidInput,
        FileSystemFailureKind.Internal,
        -> cause

        else -> BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Write,
            key = key,
            cause = cause,
        )
    }

    else -> null
}
