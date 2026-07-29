package com.artemchep.keyguard.common.service.staging

/**
 * Coarse staging lifecycle data that is safe to aggregate.
 *
 * It intentionally excludes paths, byte counts, filenames, and payload data.
 */
internal data class StagingSpoolEvent(
    val purpose: StagingPurpose,
    val outcome: StagingSpoolOutcome,
    val backing: StagingSpoolBacking,
)

internal enum class StagingSpoolOutcome {
    Sealed,
    Failed,
    Abandoned,
}

internal enum class StagingSpoolBacking {
    Memory,
    Spill,
}

internal fun interface StagingSpoolObserver {
    fun onEvent(event: StagingSpoolEvent)

    companion object {
        val NoOp = StagingSpoolObserver { }
    }
}
