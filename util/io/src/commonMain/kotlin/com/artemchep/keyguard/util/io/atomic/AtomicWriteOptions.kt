package com.artemchep.keyguard.util.io.atomic

enum class AtomicFilePermissions {
    OwnerOnly,
    ProcessDefault,
}

/**
 * Controls how the staged file is published and which access policy it uses.
 */
sealed interface AtomicPublicationPolicy {
    /**
     * Publishes only when the destination does not exist.
     */
    data class Create(
        val permissions: AtomicFilePermissions,
    ) : AtomicPublicationPolicy

    /**
     * Atomically replaces the destination when it exists and creates it
     * otherwise.
     */
    data class Replace(
        val access: ReplacementAccessPolicy,
    ) : AtomicPublicationPolicy
}

/**
 * Selects the final access policy of a replacement file.
 */
sealed interface ReplacementAccessPolicy {
    /**
     * Uses [permissions] for the replacement regardless of the destination.
     */
    data class UseRequestedPermissions(
        val permissions: AtomicFilePermissions,
    ) : ReplacementAccessPolicy

    /**
     * Preserves the existing destination's basic permissions.
     *
     * On POSIX this copies only ordinary owner/group/other rwx bits. On
     * Windows it copies the DACL and its protected/unprotected inheritance
     * state. It does not copy ownership, POSIX special bits or ACLs, Windows
     * SACLs, timestamps, extended attributes, capabilities, or security
     * labels. A missing destination is created with
     * [ifDestinationMissing].
     *
     * The permissions are captured from the regular destination observed when
     * the transaction opens, before the staged sibling is created. A
     * concurrent replacement after that point does not update the snapshot.
     * Symbolic links, Windows reparse points, and non-regular destinations are
     * rejected instead of followed.
     *
     * A platform or filesystem that cannot preserve its basic permission
     * representation fails before publication instead of silently using
     * weaker behavior. Any staged artifact is removed or remains eligible for
     * the orphan sweeper.
     */
    data class PreserveExistingBasicPermissions(
        val ifDestinationMissing: AtomicFilePermissions,
    ) : ReplacementAccessPolicy
}

enum class AtomicDirectoryPermissions {
    OwnerOnly,
    ProcessDefault,
}

/**
 * Complete policy set for one atomic publication.
 */
data class AtomicWriteOptions(
    val publication: AtomicPublicationPolicy,
    val parentDirectories: ParentDirectoryPolicy,
    val existingParentLinks: ExistingParentLinkPolicy,
    val synchronization: SynchronizationPolicy,
)

/**
 * Controls whether an atomic write owns creation of its destination directory.
 */
sealed interface ParentDirectoryPolicy {
    /**
     * Every parent component must already exist.
     */
    data object RequireExisting : ParentDirectoryPolicy

    /**
     * Creates missing components with [permissions].
     *
     * Components observed as already existing are treated as durably owned by
     * their creator; this operation persists only components it creates.
     *
     * A failed write may leave empty directories behind. They are never
     * removed automatically because another process may already be using them.
     */
    data class CreateMissing(
        val permissions: AtomicDirectoryPermissions,
    ) : ParentDirectoryPolicy
}

/**
 * Handling of symbolic links and Windows name-surrogate reparse points in
 * existing destination-parent components.
 */
enum class ExistingParentLinkPolicy {
    /**
     * Rejects every linked existing parent component.
     */
    Reject,

    /**
     * Resolves an existing link once and retains the resulting directory
     * capability. Later link retargeting does not redirect the transaction.
     */
    FollowAndPin,
}

/**
 * A completed operating-system synchronization protocol.
 *
 * These names do not promise that a filesystem, device, controller, or
 * virtualized storage stack survives power loss contrary to its documented
 * behavior.
 */
enum class SyncLevel {
    /**
     * Atomic process-visible publication without requesting a persistence
     * barrier.
     */
    ProcessAtomic,

    /** Synchronizes finalized staged bytes and file metadata before publish. */
    FileSynchronized,

    /**
     * Also completes applicable synchronization operations for parent and
     * publication directory entries owned by the transaction.
     */
    FileAndNamespaceSynchronized,
}

/**
 * Selects the synchronization level a transaction may report as successful.
 */
sealed interface SynchronizationPolicy {
    /** Requires exactly [level]; a known capability shortfall fails early. */
    data class Required(
        val level: SyncLevel,
    ) : SynchronizationPolicy

    /**
     * Prefers [preferred] while accepting a known platform-capability
     * downgrade no lower than [minimum].
     *
     * Ordinary I/O, permission, and transient failures never cause a
     * downgrade.
     */
    data class Prefer(
        val preferred: SyncLevel,
        val minimum: SyncLevel,
    ) : SynchronizationPolicy {
        init {
            require(minimum.ordinal <= preferred.ordinal) {
                "Minimum synchronization must not exceed preferred synchronization"
            }
        }
    }
}
