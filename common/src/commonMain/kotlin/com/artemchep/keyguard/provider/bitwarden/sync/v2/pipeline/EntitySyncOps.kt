package com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.time.Clock

/**
 * Outcome of a remote write operation (push or delete on server).
 *
 * - [Upsert]: the server accepted the change; the [local] entity
 *   contains the decoded server response ready for local persistence.
 * - [DeleteLocal]: the entity was deleted on the server and should
 *   be removed locally.
 * - [Failure]: the write failed, optionally carrying a decoded
 *   partial local entity from an intermediate server response.
 */
sealed interface RemoteWriteOutcome<out Local> {
    sealed interface Success<out Local> : RemoteWriteOutcome<Local>

    data class Upsert<Local>(
        val local: Local,
    ) : Success<Local>

    data object DeleteLocal : Success<Nothing>

    data class Failure<Local>(
        val partialRemoteLocal: Local?,
        val cause: Throwable,
    ) : RemoteWriteOutcome<Local>
}

data class LocalUpdateEntry<Server, Local : BitwardenService.Has<Local>>(
    val localId: String,
    val server: Server,
    val initialLocal: Local,
    val shouldUpdate: (Local?) -> Boolean,
)

data class LocalUpdateResult(
    val applied: Int,
    val skipped: Int,
)

/**
 * Entity-specific operations for the sync pipeline.
 *
 * Each entity type (cipher, folder, send, etc.) implements this
 * interface to provide database CRUD, server API calls, crypto
 * encoding/decoding, and merge logic.
 *
 * The [EntitySyncExecutor] calls these methods during execution;
 * implementations should be stateless beyond their constructor
 * dependencies.
 *
 * @param Local the local entity type (e.g. [BitwardenCipher][com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher]).
 * @param Server the server entity type (e.g. [CipherEntity][com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity]).
 */
interface EntitySyncOps<Local : BitwardenService.Has<Local>, Server : Any> {
    /** Re-reads a local entity by its primary key. */
    suspend fun readLocal(localId: String): Local?

    /** Batch-inserts or updates local entities from server data. */
    suspend fun insertOrUpdateLocally(entries: List<Pair<Server, Local?>>)

    /** Batch-updates local entities from server data with a write-boundary OCC guard. */
    suspend fun updateLocally(entries: List<LocalUpdateEntry<Server, Local>>): LocalUpdateResult

    /** Batch-deletes local entities by their IDs. */
    suspend fun deleteLocally(localIds: List<String>)

    /**
     * Persists a single local entity with the local entity version that
     * was already read by the executor before finalization.
     */
    suspend fun saveLocal(
        local: Local,
        previousLocal: Local?,
    )

    /** Uploads a local entity to the server. */
    suspend fun pushToServer(
        local: Local,
        server: Server?,
        force: Boolean,
    ): RemoteWriteOutcome<Local>

    /** Deletes an entity on the server. */
    suspend fun deleteOnServer(
        local: Local,
        serverId: String,
    ): RemoteWriteOutcome<Local>

    /** Performs a three-way merge between diverged local and server entities. */
    suspend fun mergeConflict(
        local: Local,
        server: Server,
    ): RemoteWriteOutcome<Local>

    /**
     * Merges remote metadata from a successful server write into a
     * local entity that was concurrently modified by the user.
     *
     * Preserves the user's local changes while updating the remote
     * tracking metadata (remote ID, revision date, service version)
     * and clearing stale remote-write errors.
     */
    fun mergeRemoteSuccessIntoChangedLocal(
        current: Local,
        remoteLocal: Local,
    ): Local {
        val newService =
            current.service.copy(
                remote = remoteLocal.service.remote,
                error = null,
                version = remoteLocal.service.version,
            )
        return current.withService(newService)
    }

    /**
     * Merges remote metadata from a partially successful server write
     * into a local entity that was concurrently modified by the user.
     *
     * Preserves the user's local changes while updating the remote
     * tracking metadata and recording the failed remote write.
     */
    fun mergeRemoteFailureIntoChangedLocal(
        current: Local,
        remoteLocal: Local,
        error: Throwable,
    ): Local {
        val merged =
            mergeRemoteSuccessIntoChangedLocal(
                current = current,
                remoteLocal = remoteLocal,
            )
        val newService =
            merged.service.copy(
                error = createRemoteFailureError(error),
                version = BitwardenService.VERSION,
            )
        return merged.withService(newService)
    }

    /**
     * Removes remote tracking metadata from a local entity after it
     * has been successfully deleted on the server.
     */
    fun detachRemoteAfterDeletedOnServer(current: Local): Local {
        val newService =
            current.service.copy(
                remote = null,
                error = null,
                version = BitwardenService.VERSION,
            )
        return current.withService(newService)
    }

    /**
     * Records a remote operation failure on the local entity's
     * [BitwardenService] metadata (error code, message, timestamp).
     *
     * If a [remoteLocal] partial response is available (e.g. from
     * an intermediate restore that succeeded before the PUT failed),
     * its remote metadata is incorporated.
     */
    suspend fun markRemoteFailure(
        local: Local,
        remoteLocal: Local?,
        error: Throwable,
    ): Local {
        val newRemote =
            remoteLocal?.service?.remote
                ?: local.service.remote
        val newService =
            local.service.copy(
                remote = newRemote,
                error = createRemoteFailureError(error),
                version = BitwardenService.VERSION,
            )
        return local.withService(newService)
    }

    private fun createRemoteFailureError(error: Throwable): BitwardenService.Error {
        val code =
            when (error) {
                is ApiException -> error.code.value
                is HttpException -> error.statusCode.value
                else -> BitwardenService.Error.CODE_UNKNOWN
            }
        return BitwardenService.Error(
            code = code,
            message = error.message,
            revisionDate = Clock.System.now(),
        )
    }
}
