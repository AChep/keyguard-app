package com.artemchep.keyguard.provider.bitwarden.sync.v2.core

/**
 * Outcome of syncing a single entity type (e.g. ciphers, folders).
 *
 * - [Completed] means the pipeline ran to completion (though individual
 *   actions may have failed — see [SyncExecutionResult.failures]).
 * - [Failed] means the entity type sync threw before or during execution.
 */
sealed interface EntityTypeOutcome {
    data class Completed(
        val result: SyncExecutionResult,
    ) : EntityTypeOutcome

    data class Failed(
        val error: Throwable,
    ) : EntityTypeOutcome
}

/**
 * Aggregated result across all entity types for a single sync cycle.
 *
 * @param outcomes map of entity type name (e.g. "ciphers") to its outcome.
 */
data class SyncResult(
    val outcomes: Map<String, EntityTypeOutcome>,
) {
    /**
     * Skipped actions are intentionally treated as not fully successful.
     *
     * An optimistic concurrency-control (OCC) skip means an entity changed
     * locally while this sync cycle was applying server state. In that case
     * the server revision date must not be cached; otherwise the next cycle
     * could skip the full sync while some entities still need reconciliation.
     */
    val isFullySuccessful: Boolean
        get() =
            outcomes.values.all { outcome ->
                outcome is EntityTypeOutcome.Completed &&
                    outcome.result.skipped == 0 &&
                    outcome.result.failures.isEmpty()
            }

    val totalSucceeded: Int
        get() =
            outcomes.values.sumOf {
                (it as? EntityTypeOutcome.Completed)?.result?.succeeded ?: 0
            }

    val totalSkipped: Int
        get() =
            outcomes.values.sumOf {
                (it as? EntityTypeOutcome.Completed)?.result?.skipped ?: 0
            }

    val totalActionFailures: Int
        get() =
            outcomes.values.sumOf {
                (it as? EntityTypeOutcome.Completed)?.result?.failures?.size ?: 0
            }

    val totalEntityTypeFailures: Int
        get() = outcomes.values.count { it is EntityTypeOutcome.Failed }
}

/**
 * Throws if the sync result is not fully clean, preventing the
 * server revision date from being cached.
 *
 * A cached revision date tells the next sync cycle "nothing changed"
 * — so we must only cache it when every entity type completed with
 * zero failures and zero skips. Otherwise the next sync would skip
 * work that still needs to happen.
 */
internal fun SyncResult.requireCleanForRevisionCache() {
    val firstEntityTypeFailure =
        outcomes.values
            .filterIsInstance<EntityTypeOutcome.Failed>()
            .firstOrNull()
    if (firstEntityTypeFailure != null) {
        throw firstEntityTypeFailure.error
    }

    val firstActionFailure =
        outcomes.values
            .filterIsInstance<EntityTypeOutcome.Completed>()
            .firstNotNullOfOrNull { it.result.failures.firstOrNull() }
    if (firstActionFailure != null) {
        throw IllegalStateException(
            "Sync completed with $totalActionFailures action failure(s).",
            firstActionFailure.error,
        )
    }

    if (totalSkipped > 0) {
        throw IllegalStateException(
            "Sync completed with $totalSkipped skipped action(s).",
        )
    }
}
