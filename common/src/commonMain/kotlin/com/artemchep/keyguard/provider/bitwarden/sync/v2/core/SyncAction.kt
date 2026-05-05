package com.artemchep.keyguard.provider.bitwarden.sync.v2.core

/**
 * Represents a single synchronization action determined by [SyncDiffer].
 *
 * Each subtype encodes the direction and intent of the action:
 * - **Local** actions modify the local database only.
 * - **Server** actions modify the remote server and then finalize locally.
 * - **Merge** actions reconcile diverged local and server state.
 */
sealed interface SyncAction {
    /** Insert a new server entity into the local database. */
    data class InsertLocally(
        val serverId: String,
    ) : SyncAction

    /**
     * Overwrite a local entity with the server version.
     *
     * @param force when `true`, bypasses date comparison (used for
     *   service-version upgrades that require re-decoding).
     */
    data class UpdateLocally(
        val localId: String,
        val serverId: String,
        val force: Boolean = false,
    ) : SyncAction

    /** Delete a local entity that no longer exists on the server. */
    data class DeleteLocally(
        val localId: String,
    ) : SyncAction

    /**
     * Upload a locally changed entity to the server.
     *
     * @param serverId `null` for newly created local entities.
     * @param force when `true`, pushes even if dates match (used for
     *   repair scenarios like timestamp precision mismatches).
     */
    data class PushToServer(
        val localId: String,
        val serverId: String?,
        val force: Boolean = false,
    ) : SyncAction

    /** Delete an entity on the server that was locally deleted. */
    data class DeleteOnServer(
        val localId: String,
        val serverId: String,
    ) : SyncAction

    /**
     * Three-way merge for entities that diverged on both sides.
     *
     * Only applicable to mergeable entity types (e.g. ciphers).
     */
    data class MergeConflict(
        val localId: String,
        val serverId: String,
    ) : SyncAction
}
