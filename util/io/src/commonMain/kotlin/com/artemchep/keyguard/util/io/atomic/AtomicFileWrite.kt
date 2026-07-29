package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import kotlinx.io.Sink

/**
 * Writes and atomically publishes one file.
 *
 * [destination] must be an absolute local path without parent (`..`)
 * components.
 *
 * Parent-directory behavior is selected explicitly with
 * [AtomicWriteOptions.parentDirectories], and the temporary is created
 * exclusively inside the resolved destination directory. A failed write
 * never truncates the destination.
 *
 * Existing-link handling and synchronization are explicit. No foundational
 * API silently assumes a namespace trust or persistence contract.
 */
@OptIn(InternalKeyguardIoApi::class)
fun <T> writeFileAtomically(
    destination: LocalPath,
    options: AtomicWriteOptions,
    write: (Sink) -> T,
): AtomicWriteResult<T> =
    openAtomicFileTransaction(
        destination = destination,
        options = options,
    ).use { transaction ->
        transaction.writeAndCommit(write)
    }

/**
 * Writes beneath an existing trusted root retained for the transaction.
 *
 * Descendant links are always rejected. A contradictory link policy fails at
 * the API boundary rather than being silently weakened or overridden.
 */
@OptIn(InternalKeyguardIoApi::class)
fun <T> writeFileAtomically(
    destination: AtomicFileDestination,
    options: AtomicWriteOptions,
    write: (Sink) -> T,
): AtomicWriteResult<T> {
    require(options.existingParentLinks == ExistingParentLinkPolicy.Reject) {
        "AtomicFileDestination requires ExistingParentLinkPolicy.Reject"
    }
    return openAtomicDirectory(destination.root).use { directory ->
        directory.openAtomicFileTransaction(
            relativeDestination = destination.relativePath,
            options = options,
        ).use { transaction ->
            transaction.writeAndCommit(write)
        }
    }
}

/**
 * Writes and atomically publishes one owner-only file.
 *
 * A convenience over [writeFileAtomically] for private artifacts (vault
 * files, encrypted outputs): existing basic permissions are deliberately
 * ignored and access is restricted to the process owner. The result retains
 * the synchronization and cleanup receipt.
 */
fun <T> writePrivatelyAtomically(
    destination: AtomicFileDestination,
    parentDirectories: ParentDirectoryPolicy,
    synchronization: SynchronizationPolicy,
    write: (Sink) -> T,
): AtomicWriteResult<T> = writeFileAtomically(
    destination = destination,
    options = AtomicWriteOptions(
        publication = AtomicPublicationPolicy.Replace(
            access = ReplacementAccessPolicy.UseRequestedPermissions(
                permissions = AtomicFilePermissions.OwnerOnly,
            ),
        ),
        parentDirectories = parentDirectories,
        existingParentLinks = ExistingParentLinkPolicy.Reject,
        synchronization = synchronization,
    ),
    write = write,
)
