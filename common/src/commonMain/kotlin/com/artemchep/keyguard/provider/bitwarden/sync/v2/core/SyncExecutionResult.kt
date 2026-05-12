package com.artemchep.keyguard.provider.bitwarden.sync.v2.core

/**
 * Aggregated result of executing all [SyncAction]s for a single entity type.
 *
 * Individual action failures are recorded but do not abort the overall
 * execution — other actions continue to completion.
 *
 * @param succeeded number of actions that completed successfully.
 * @param skipped number of actions skipped due to optimistic
 *   concurrency checks (the entity was modified concurrently).
 * @param failures actions that failed, with their associated errors.
 * @param staleServerEntities number of server entities older than the
 *   locally recorded last-synced revision.
 */
data class SyncExecutionResult(
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failures: List<ActionFailure> = emptyList(),
    val staleServerEntities: Int = 0,
) {
    val total: Int get() = succeeded + skipped + failures.size
}

/** A single action that failed during sync execution. */
data class ActionFailure(
    val action: SyncAction,
    val error: Throwable,
)
