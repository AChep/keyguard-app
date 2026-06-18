package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.BulkRemoteOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import kotlin.time.Instant

internal val T0: Instant = Instant.parse("2024-01-01T00:00:00Z")
internal val T1: Instant = Instant.parse("2024-01-01T00:00:01Z")
internal val T2: Instant = Instant.parse("2024-01-01T00:00:02Z")
internal val T3: Instant = Instant.parse("2024-01-01T00:00:03Z")
internal val T4: Instant = Instant.parse("2024-01-01T00:00:04Z")

internal data class TestLocal(
    val id: String,
    val body: String = id,
    val revisionDate: Instant = T0,
    val deletedDate: Instant? = null,
    override val service: BitwardenService = testService(),
    val mergeable: Boolean = true,
    val hasErrorOverride: Boolean = false,
    val canRetryError: Boolean = true,
    val attachmentIds: Set<String>? = null,
    val folderId: String? = null,
    val favorite: Boolean? = null,
    val collectionIds: Set<String>? = null,
    val requiresLocalRefreshWhenDatesMatch: Boolean = false,
    val requiresPushWhenDatesMatch: Boolean = false,
    val requiresForcePushWhenDatesMatch: Boolean = false,
    val pendingLocalAttachmentIds: Set<String> = emptySet(),
    val pendingRemoteAttachmentDeletionIds: Set<String> = emptySet(),
) : BitwardenService.Has<TestLocal> {
    override fun withService(service: BitwardenService): TestLocal = copy(service = service)
}

internal data class TestServer(
    val id: String,
    val body: String = id,
    val revisionDate: Instant = T0,
    val deletedDate: Instant? = null,
    val attachmentIds: Set<String>? = null,
    val folderId: String? = null,
    val favorite: Boolean? = null,
    val collectionIds: Set<String>? = null,
)

internal object TestSyncStrategy : EntitySyncStrategy<TestLocal, TestServer> {
    override fun toLocalItemMeta(entity: TestLocal): LocalItemMeta =
        LocalItemMeta(
            localId = entity.id,
            remoteId = entity.service.remote?.id,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            lastSyncedRevisionDate = entity.service.remote?.revisionDate,
            lastSyncedDeletedDate = entity.service.remote?.deletedDate,
            isLocallyDeleted = entity.service.deleted,
            isMergeable = entity.mergeable,
            serviceVersion = entity.service.version,
            hasError = entity.hasErrorOverride || entity.service.error != null,
            canRetryError = entity.canRetryError,
            attachmentIds = entity.attachmentIds,
            localFolderId = entity.folderId,
            favorite = entity.favorite,
            collectionIds = entity.collectionIds,
            requiresLocalRefreshWhenDatesMatch = entity.requiresLocalRefreshWhenDatesMatch,
            requiresPushWhenDatesMatch = entity.requiresPushWhenDatesMatch,
            requiresForcePushWhenDatesMatch = entity.requiresForcePushWhenDatesMatch,
            pendingLocalAttachmentIds = entity.pendingLocalAttachmentIds,
            pendingRemoteAttachmentDeletionIds = entity.pendingRemoteAttachmentDeletionIds,
        )

    override fun toServerItemMeta(entity: TestServer): ServerItemMeta =
        ServerItemMeta(
            id = entity.id,
            revisionDate = entity.revisionDate,
            deletedDate = entity.deletedDate,
            attachmentIds = entity.attachmentIds,
            localFolderId = entity.folderId,
            favorite = entity.favorite,
            collectionIds = entity.collectionIds,
        )
}

internal class TestEntitySyncOps(
    initialLocals: List<TestLocal>,
) : EntitySyncOps<TestLocal, TestServer> {
    val locals: MutableMap<String, TestLocal> =
        initialLocals.associateBy { it.id }.toMutableMap()
    val calls = mutableListOf<SyncCall>()

    var onReadLocal: suspend (String, TestLocal?) -> TestLocal? = { _, local -> local }
    var onInsertOrUpdate: suspend (List<Pair<TestServer, TestLocal?>>) -> Unit = { entries ->
        entries.forEach { (server, current) ->
            val localId = current?.id ?: server.id
            locals[localId] = server.toLocal(current)
        }
    }
    var onBeforeUpdateGuard: suspend () -> Unit = {}
    var onUpdateLocally: suspend (List<LocalUpdateEntry<TestServer, TestLocal>>) -> LocalUpdateResult = { entries ->
        var applied = 0
        var skipped = 0
        entries.forEach { entry ->
            calls += SyncCall.ReadLocal(entry.localId)
            val current = locals[entry.localId]
            if (entry.shouldUpdate(current)) {
                locals[entry.localId] = entry.server.toLocal(entry.initialLocal)
                applied++
            } else {
                skipped++
            }
        }
        LocalUpdateResult(
            applied = applied,
            skipped = skipped,
        )
    }
    var onDeleteLocally: suspend (List<String>) -> Unit = { ids ->
        ids.forEach(locals::remove)
    }
    var onSaveLocal: suspend (TestLocal) -> Unit = { local ->
        locals[local.id] = local
    }
    var onPushToServer: suspend (
        local: TestLocal,
        server: TestServer?,
        force: Boolean,
    ) -> RemoteWriteOutcome<TestLocal> = { local, server, _ ->
        val remoteId = server?.id ?: "remote-${local.id}"
        RemoteWriteOutcome.Upsert(
            local.copy(
                revisionDate = T4,
                service = testService(remoteId = remoteId, remoteRevisionDate = T4),
            ),
        )
    }
    var onDeleteOnServer: suspend (
        local: TestLocal,
        serverId: String,
    ) -> RemoteWriteOutcome<TestLocal> = { _, _ ->
        RemoteWriteOutcome.DeleteLocal
    }
    var onMergeConflict: suspend (
        local: TestLocal,
        server: TestServer,
    ) -> RemoteWriteOutcome<TestLocal> = { local, server ->
        RemoteWriteOutcome.Upsert(
            local.copy(
                body = "${local.body}|${server.body}",
                revisionDate = T4,
                service = testService(remoteId = server.id, remoteRevisionDate = T4),
            ),
        )
    }

    override suspend fun readLocal(localId: String): TestLocal? {
        calls += SyncCall.ReadLocal(localId)
        return onReadLocal(localId, locals[localId])
    }

    override suspend fun insertOrUpdateLocally(entries: List<Pair<TestServer, TestLocal?>>) {
        calls += SyncCall.InsertOrUpdateLocally(
            entries.map { (server, local) -> server.id to local?.id },
        )
        onInsertOrUpdate(entries)
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<TestServer, TestLocal>>,
    ): LocalUpdateResult {
        calls += SyncCall.UpdateLocally(
            entries.map { entry -> entry.server.id to entry.initialLocal.id },
        )
        onBeforeUpdateGuard()
        return onUpdateLocally(entries)
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        calls += SyncCall.DeleteLocally(localIds)
        onDeleteLocally(localIds)
    }

    override suspend fun saveLocal(
        local: TestLocal,
        previousLocal: TestLocal?,
    ) {
        calls += SyncCall.SaveLocal(local.id)
        onSaveLocal(local)
    }

    override suspend fun pushToServer(
        local: TestLocal,
        server: TestServer?,
        force: Boolean,
    ): RemoteWriteOutcome<TestLocal> {
        calls += SyncCall.PushToServer(
            localId = local.id,
            serverId = server?.id,
            force = force,
        )
        return onPushToServer(local, server, force)
    }

    override suspend fun deleteOnServer(
        local: TestLocal,
        serverId: String,
    ): RemoteWriteOutcome<TestLocal> {
        calls += SyncCall.DeleteOnServer(
            localId = local.id,
            serverId = serverId,
        )
        return onDeleteOnServer(local, serverId)
    }

    override suspend fun mergeConflict(
        local: TestLocal,
        server: TestServer,
    ): RemoteWriteOutcome<TestLocal> {
        calls += SyncCall.MergeConflict(
            localId = local.id,
            serverId = server.id,
        )
        return onMergeConflict(local, server)
    }
}

internal class TestBulkRemoteOps(
    private val fail: Boolean = false,
) : BulkRemoteOps<TestLocal> {
    val chunks = mutableListOf<List<Pair<TestLocal, String>>>()

    override suspend fun bulkDeleteOnServer(entries: List<Pair<TestLocal, String>>) {
        chunks += entries
        if (fail) {
            error("bulk delete failed")
        }
    }

    override suspend fun bulkRestoreOnServer(serverIds: List<String>) {
        error("unused in tests: $serverIds")
    }

    override suspend fun bulkTrashOnServer(serverIds: List<String>) {
        error("unused in tests: $serverIds")
    }
}

internal sealed interface SyncCall {
    data class ReadLocal(
        val localId: String,
    ) : SyncCall

    data class InsertOrUpdateLocally(
        val entries: List<Pair<String, String?>>,
    ) : SyncCall

    data class UpdateLocally(
        val entries: List<Pair<String, String>>,
    ) : SyncCall

    data class DeleteLocally(
        val localIds: List<String>,
    ) : SyncCall

    data class SaveLocal(
        val localId: String,
    ) : SyncCall

    data class PushToServer(
        val localId: String,
        val serverId: String?,
        val force: Boolean,
    ) : SyncCall

    data class DeleteOnServer(
        val localId: String,
        val serverId: String,
    ) : SyncCall

    data class MergeConflict(
        val localId: String,
        val serverId: String,
    ) : SyncCall
}

internal fun testService(
    remoteId: String? = null,
    remoteRevisionDate: Instant? = null,
    remoteDeletedDate: Instant? = null,
    deleted: Boolean = false,
    version: Int = BitwardenService.VERSION,
    error: BitwardenService.Error? = null,
): BitwardenService =
    BitwardenService(
        remote =
            remoteId?.let {
                BitwardenService.Remote(
                    id = it,
                    revisionDate = remoteRevisionDate ?: T0,
                    deletedDate = remoteDeletedDate,
                )
            },
        error = error,
        deleted = deleted,
        version = version,
    )

internal fun syncedLocal(
    localId: String,
    remoteId: String = localId,
    body: String = localId,
    revisionDate: Instant = T0,
    deletedDate: Instant? = null,
): TestLocal =
    TestLocal(
        id = localId,
        body = body,
        revisionDate = revisionDate,
        deletedDate = deletedDate,
        service =
            testService(
                remoteId = remoteId,
                remoteRevisionDate = revisionDate,
                remoteDeletedDate = deletedDate,
            ),
    )

internal fun changedLocal(
    localId: String,
    remoteId: String = localId,
    body: String = localId,
    baseRevisionDate: Instant = T0,
    revisionDate: Instant = T1,
): TestLocal =
    TestLocal(
        id = localId,
        body = body,
        revisionDate = revisionDate,
        service =
            testService(
                remoteId = remoteId,
                remoteRevisionDate = baseRevisionDate,
            ),
    )

internal fun localOnly(
    localId: String,
    body: String = localId,
    revisionDate: Instant = T1,
): TestLocal =
    TestLocal(
        id = localId,
        body = body,
        revisionDate = revisionDate,
        service = testService(),
    )

internal fun TestServer.toLocal(current: TestLocal?): TestLocal =
    (current ?: TestLocal(id = id)).copy(
        body = body,
        revisionDate = revisionDate,
        deletedDate = deletedDate,
        service =
            testService(
                remoteId = id,
                remoteRevisionDate = revisionDate,
                remoteDeletedDate = deletedDate,
            ),
        attachmentIds = attachmentIds,
        folderId = folderId,
        favorite = favorite,
        collectionIds = collectionIds,
    )
