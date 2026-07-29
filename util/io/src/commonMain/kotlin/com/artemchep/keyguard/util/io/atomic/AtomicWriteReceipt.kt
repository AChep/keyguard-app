package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.FileSystemFailure

/**
 * Synchronization level actually achieved by a completed write.
 */
enum class AchievedSyncLevel {
    ProcessAtomic,
    FileSynchronized,
    FileAndNamespaceSynchronized,
}

enum class AtomicPublicationState {
    /** The destination was not changed by this operation. */
    NotPublished,

    /** The destination contains the staged bytes. */
    Published,

    /**
     * The destination contains the staged bytes, but the post-rename
     * directory flush failed, so durability cannot be trusted until a later
     * successful durable write.
     */
    PublishedSyncUnknown,

    /**
     * Synchronization is unknown and post-publication cleanup or handle
     * finalization was also incomplete.
     */
    PublishedSyncUnknownCleanupIncomplete,

    /**
     * A namespace mutation may have been applied, but publication could not be
     * positively established. This state never authorizes blind retry, marker
     * advancement, or source deletion.
     */
    Unknown,
}

/**
 * Native namespace primitive whose result determines publication.
 */
enum class AtomicPublicationOperation {
    Rename,
    HardLink,
}

/**
 * Success-path report of a completed atomic write.
 */
data class AtomicWriteReceipt(
    /** Caller-selected synchronization contract. */
    val requestedSynchronization: SynchronizationPolicy,
    /** Synchronization level actually completed by the native transaction. */
    val achievedSyncLevel: AchievedSyncLevel,
    /**
     * Native cleanup failure, when one was observed.
     */
    val cleanupFailure: FileSystemFailure? = null,
    /**
     * Whether temporary cleanup or handle finalization was incomplete.
     *
     * This can be true while [cleanupFailure] is null when cleanup was
     * deliberately skipped because it could have deleted a destination whose
     * publication acknowledgement was lost. Any residual named artifact
     * remains recognizable to the orphan sweeper.
     */
    val cleanupIncomplete: Boolean = cleanupFailure != null,
) {
    init {
        require(cleanupIncomplete || cleanupFailure == null) {
            "A cleanup failure requires incomplete cleanup"
        }
    }

    val publicationState: AtomicPublicationState
        get() = AtomicPublicationState.Published

    /**
     * Whether [SynchronizationPolicy.Prefer] selected a lower, still
     * acceptable level because of a known platform capability ceiling.
     */
    val capabilityDowngraded: Boolean
        get() = when (val policy = requestedSynchronization) {
            is SynchronizationPolicy.Required -> false

            is SynchronizationPolicy.Prefer ->
                achievedSyncLevel.ordinal < policy.preferred.ordinal
        }

    /**
     * Returns this receipt when temporary cleanup and handle finalization
     * completed.
     *
     * @throws AtomicCleanupIncompleteException after successful publication
     * when cleanup or finalization was incomplete.
     */
    fun requireCleanupComplete(): AtomicWriteReceipt {
        if (!cleanupIncomplete) return this
        throw AtomicCleanupIncompleteException(
            message = "Atomic write was published but cleanup was incomplete",
            achievedSyncLevel = achievedSyncLevel,
            cleanupFailure = cleanupFailure,
        )
    }
}

/**
 * The caller's value together with the write receipt.
 */
data class AtomicWriteResult<T>(
    val value: T,
    val receipt: AtomicWriteReceipt,
)
