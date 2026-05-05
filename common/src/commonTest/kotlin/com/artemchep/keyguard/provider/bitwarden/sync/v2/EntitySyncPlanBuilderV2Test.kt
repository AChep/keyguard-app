package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncPlanBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class EntitySyncPlanBuilderV2Test {
    @Test
    fun `build snapshots preserve entity lookup maps and extracted metadata`() {
        val localA = syncedLocal(localId = "local-a", remoteId = "remote-a")
        val localB = localOnly(localId = "local-b")
        val serverA = TestServer(id = "remote-a", revisionDate = T0)
        val serverC = TestServer(id = "remote-c", revisionDate = T1)
        val builder = EntitySyncPlanBuilder(TestSyncStrategy)

        val localSnapshot = builder.buildLocalSnapshot(listOf(localA, localB))
        val serverSnapshot = builder.buildServerSnapshot(listOf(serverA, serverC))

        assertEquals(setOf("local-a", "local-b"), localSnapshot.entitiesByLocalId.keys)
        assertEquals(localA, localSnapshot.entitiesByLocalId["local-a"])
        assertEquals(listOf("local-a", "local-b"), localSnapshot.metadata.map { it.localId })
        assertEquals(setOf("remote-a", "remote-c"), serverSnapshot.entitiesById.keys)
        assertEquals(serverC, serverSnapshot.entitiesById["remote-c"])
        assertEquals(listOf("remote-a", "remote-c"), serverSnapshot.metadata.map { it.id })
    }

    @Test
    fun `build plan runs snapshot metadata through the sync differ`() {
        val localClean = syncedLocal(localId = "local-a", remoteId = "remote-a")
        val localPending = localOnly(localId = "local-new")
        val serverChanged = TestServer(id = "remote-a", revisionDate = T1)
        val serverNew = TestServer(id = "remote-new", revisionDate = T1)
        val builder = EntitySyncPlanBuilder(TestSyncStrategy)

        val plan = builder.buildPlan(
            localEntities = listOf(localClean, localPending),
            serverEntities = listOf(serverChanged, serverNew),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-a",
                    serverId = "remote-a",
                ),
                SyncAction.InsertLocally(serverId = "remote-new"),
                SyncAction.PushToServer(
                    localId = "local-new",
                    serverId = null,
                ),
            ),
            plan.actions,
        )
        assertEquals(localClean, plan.localSnapshot.entitiesByLocalId["local-a"])
        assertEquals(serverChanged, plan.serverSnapshot.entitiesById["remote-a"])
    }

    @Test
    fun `build plan emits primary differ actions from extracted metadata`() {
        val remoteChanged = syncedLocal(localId = "local-update", remoteId = "remote-update")
        val localDeleted =
            changedLocal(localId = "local-delete", remoteId = "remote-delete")
                .copy(
                    deletedDate = T1,
                    service =
                        testService(
                            remoteId = "remote-delete",
                            remoteRevisionDate = T0,
                            deleted = true,
                        ),
                )
        val missingRemote = syncedLocal(localId = "local-missing", remoteId = "remote-missing")
        val localNew = localOnly(localId = "local-new")
        val builder = EntitySyncPlanBuilder(TestSyncStrategy)

        val plan = builder.buildPlan(
            localEntities = listOf(remoteChanged, localDeleted, missingRemote, localNew),
            serverEntities = listOf(
                TestServer(id = "remote-update", revisionDate = T1),
                TestServer(id = "remote-delete", revisionDate = T0),
                TestServer(id = "remote-new", revisionDate = T1),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-update",
                    serverId = "remote-update",
                ),
                SyncAction.DeleteOnServer(
                    localId = "local-delete",
                    serverId = "remote-delete",
                ),
                SyncAction.InsertLocally(serverId = "remote-new"),
                SyncAction.DeleteLocally(localId = "local-missing"),
                SyncAction.PushToServer(
                    localId = "local-new",
                    serverId = null,
                ),
            ),
            plan.actions,
        )
    }

    @Test
    fun `build plan preserves strategy flags for force refresh and force push repairs`() {
        val oldServiceVersion =
            syncedLocal(localId = "local-old-version", remoteId = "remote-old-version")
                .copy(
                    service =
                        testService(
                            remoteId = "remote-old-version",
                            remoteRevisionDate = T0,
                            version = BitwardenService.VERSION - 1,
                        ),
                )
        val forcePush =
            syncedLocal(localId = "local-force-push", remoteId = "remote-force-push")
                .copy(requiresForcePushWhenDatesMatch = true)
        val retryableError =
            syncedLocal(localId = "local-error", remoteId = "remote-error")
                .copy(
                    hasErrorOverride = true,
                    canRetryError = false,
                )
        val builder = EntitySyncPlanBuilder(TestSyncStrategy)

        val plan = builder.buildPlan(
            localEntities = listOf(oldServiceVersion, forcePush, retryableError),
            serverEntities = listOf(
                TestServer(id = "remote-old-version", revisionDate = T0),
                TestServer(id = "remote-force-push", revisionDate = T0),
                TestServer(id = "remote-error", revisionDate = T0),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-old-version",
                    serverId = "remote-old-version",
                    force = true,
                ),
                SyncAction.PushToServer(
                    localId = "local-force-push",
                    serverId = "remote-force-push",
                    force = true,
                ),
                SyncAction.UpdateLocally(
                    localId = "local-error",
                    serverId = "remote-error",
                ),
            ),
            plan.actions,
        )
    }

    @Test
    fun `build plan keeps duplicate local metadata visible to the differ while entity map uses latest local`() {
        val staleDuplicate = syncedLocal(localId = "local-old", remoteId = "remote-duplicate")
        val winnerDuplicate = syncedLocal(
            localId = "local-new",
            remoteId = "remote-duplicate",
            revisionDate = T1,
        )
        val builder = EntitySyncPlanBuilder(TestSyncStrategy)

        val plan = builder.buildPlan(
            localEntities = listOf(staleDuplicate, winnerDuplicate),
            serverEntities = listOf(TestServer(id = "remote-duplicate", revisionDate = T1)),
        )

        assertEquals(
            listOf(SyncAction.DeleteLocally(localId = "local-old")),
            plan.actions,
        )
        assertEquals(listOf("local-old", "local-new"), plan.localSnapshot.metadata.map { it.localId })
        assertEquals(staleDuplicate, plan.localSnapshot.entitiesByLocalId["local-old"])
        assertEquals(winnerDuplicate, plan.localSnapshot.entitiesByLocalId["local-new"])
    }
}
