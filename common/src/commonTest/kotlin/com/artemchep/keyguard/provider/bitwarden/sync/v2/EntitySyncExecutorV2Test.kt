package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalEntitySnapshot
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerEntitySnapshot
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncExecutor
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class EntitySyncExecutorV2Test {
    @Test
    fun `executor runs actions in deterministic sync phases`() = runTest {
        val locals = listOf(
            syncedLocal(localId = "local-delete", remoteId = "remote-delete"),
            syncedLocal(localId = "local-update", remoteId = "remote-update"),
            changedLocal(localId = "local-merge", remoteId = "remote-merge"),
            changedLocal(localId = "local-delete-server", remoteId = "remote-delete-server"),
            changedLocal(localId = "local-push", remoteId = "remote-push"),
        )
        val servers = listOf(
            TestServer(id = "remote-insert", revisionDate = T1),
            TestServer(id = "remote-update", revisionDate = T1),
            TestServer(id = "remote-merge", revisionDate = T2),
            TestServer(id = "remote-push", revisionDate = T0),
        )
        val ops = TestEntitySyncOps(locals)
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = locals,
                servers = servers,
                actions = listOf(
                    SyncAction.PushToServer(
                        localId = "local-push",
                        serverId = "remote-push",
                    ),
                    SyncAction.DeleteOnServer(
                        localId = "local-delete-server",
                        serverId = "remote-delete-server",
                    ),
                    SyncAction.MergeConflict(
                        localId = "local-merge",
                        serverId = "remote-merge",
                    ),
                    SyncAction.UpdateLocally(
                        localId = "local-update",
                        serverId = "remote-update",
                    ),
                    SyncAction.InsertLocally(serverId = "remote-insert"),
                    SyncAction.DeleteLocally(localId = "local-delete"),
                ),
            ),
        )

        assertEquals(6, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(emptyList(), result.failures)
        assertEquals(
            listOf(
                SyncCall.DeleteLocally(listOf("local-delete")),
                SyncCall.InsertOrUpdateLocally(listOf("remote-insert" to null)),
                SyncCall.UpdateLocally(listOf("remote-update" to "local-update")),
                SyncCall.MergeConflict(
                    localId = "local-merge",
                    serverId = "remote-merge",
                ),
                SyncCall.DeleteOnServer(
                    localId = "local-delete-server",
                    serverId = "remote-delete-server",
                ),
                SyncCall.DeleteLocally(listOf("local-delete-server")),
                SyncCall.PushToServer(
                    localId = "local-push",
                    serverId = "remote-push",
                    force = false,
                ),
            ),
            ops.calls.filterNot { it is SyncCall.ReadLocal || it is SyncCall.SaveLocal },
        )
    }

    @Test
    fun `executor skips local update when entity changed after planning`() = runTest {
        val plannedLocal = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val concurrentlyEditedLocal = plannedLocal.copy(revisionDate = T1)
        val server = TestServer(id = "remote-1", revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(concurrentlyEditedLocal))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(plannedLocal),
                servers = listOf(server),
                actions = listOf(
                    SyncAction.UpdateLocally(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(
            emptyList(),
            ops.calls.filterIsInstance<SyncCall.UpdateLocally>(),
        )
    }

    @Test
    fun `delete locally treats already deleted entity as success`() = runTest {
        val plannedLocal = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val ops = TestEntitySyncOps(emptyList())
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(plannedLocal),
                servers = emptyList(),
                actions = listOf(SyncAction.DeleteLocally(localId = "local-1")),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.DeleteLocally>())
    }

    @Test
    fun `delete locally skips entity modified after planning`() = runTest {
        val plannedLocal = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val concurrentlyEditedLocal = plannedLocal.copy(revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(concurrentlyEditedLocal))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(plannedLocal),
                servers = emptyList(),
                actions = listOf(SyncAction.DeleteLocally(localId = "local-1")),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.DeleteLocally>())
        assertEquals(concurrentlyEditedLocal, ops.locals.getValue("local-1"))
    }

    @Test
    fun `delete locally batch failure records all safe actions as failures`() = runTest {
        val locals = listOf(
            syncedLocal(localId = "local-1", remoteId = "remote-1"),
            syncedLocal(localId = "local-2", remoteId = "remote-2"),
        )
        val ops = TestEntitySyncOps(locals)
        ops.onDeleteLocally = {
            throw IllegalStateException("db delete failed")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = locals,
                servers = emptyList(),
                actions = locals.map { SyncAction.DeleteLocally(localId = it.id) },
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(2, result.failures.size)
        assertEquals(
            listOf("db delete failed", "db delete failed"),
            result.failures.map { it.error.message },
        )
    }

    @Test
    fun `delete locally cancellation propagates immediately`() = runTest {
        val local = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val ops = TestEntitySyncOps(listOf(local))
        ops.onDeleteLocally = {
            throw CancellationException("delete cancelled")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = listOf(local),
                    servers = emptyList(),
                    actions = listOf(SyncAction.DeleteLocally(localId = "local-1")),
                ),
            )
        }
    }

    @Test
    fun `insert locally batch failure records only attempted actions as failures`() = runTest {
        val servers = listOf(
            TestServer(id = "remote-1"),
            TestServer(id = "remote-2"),
        )
        val ops = TestEntitySyncOps(emptyList())
        ops.onInsertOrUpdate = {
            throw IllegalStateException("db insert failed")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = emptyList(),
                servers = servers,
                actions = listOf(
                    SyncAction.InsertLocally(serverId = "remote-1"),
                    SyncAction.InsertLocally(serverId = "remote-missing"),
                    SyncAction.InsertLocally(serverId = "remote-2"),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(2, result.failures.size)
        assertEquals(
            listOf("db insert failed", "db insert failed"),
            result.failures.map { it.error.message },
        )
        assertEquals(
            listOf(
                SyncAction.InsertLocally(serverId = "remote-1"),
                SyncAction.InsertLocally(serverId = "remote-2"),
            ),
            result.failures.map { it.action },
        )
    }

    @Test
    fun `insert locally tracks missing server snapshot entity as skipped`() = runTest {
        val ops = TestEntitySyncOps(emptyList())
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            EntitySyncPlan(
                actions = listOf(SyncAction.InsertLocally(serverId = "remote-missing")),
                localSnapshot = LocalEntitySnapshot(
                    entitiesByLocalId = emptyMap(),
                    metadata = emptyList(),
                ),
                serverSnapshot = ServerEntitySnapshot(
                    entitiesById = emptyMap(),
                    metadata = emptyList(),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(1, result.total)
        assertEquals(emptyList(), result.failures)
        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.InsertOrUpdateLocally>())
    }

    @Test
    fun `insert locally cancellation propagates`() = runTest {
        val server = TestServer(id = "remote-1")
        val ops = TestEntitySyncOps(emptyList())
        ops.onInsertOrUpdate = {
            throw CancellationException("insert cancelled")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = emptyList(),
                    servers = listOf(server),
                    actions = listOf(SyncAction.InsertLocally(serverId = "remote-1")),
                ),
            )
        }
    }

    @Test
    fun `delete cancellation prevents later insert phase from starting`() = runTest {
        val local = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val server = TestServer(id = "remote-insert")
        val ops = TestEntitySyncOps(listOf(local))
        ops.onDeleteLocally = { ids ->
            ids.forEach(ops.locals::remove)
            throw CancellationException("cancelled during delete phase")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = listOf(local),
                    servers = listOf(server),
                    actions = listOf(
                        SyncAction.DeleteLocally(localId = "local-1"),
                        SyncAction.InsertLocally(serverId = "remote-insert"),
                    ),
                ),
            )
        }

        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.InsertOrUpdateLocally>())
    }

    @Test
    fun `local update reads each entity before decode and at guarded write`() = runTest {
        val plannedLocal = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val server = TestServer(id = "remote-1", revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(plannedLocal))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(plannedLocal),
                servers = listOf(server),
                actions = listOf(
                    SyncAction.UpdateLocally(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(
            listOf(
                SyncCall.ReadLocal(localId = "local-1"),
                SyncCall.ReadLocal(localId = "local-1"),
            ),
            ops.calls.filterIsInstance<SyncCall.ReadLocal>(),
        )
        assertEquals(
            listOf(SyncCall.UpdateLocally(listOf("remote-1" to "local-1"))),
            ops.calls.filterIsInstance<SyncCall.UpdateLocally>(),
        )
    }

    @Test
    fun `local update skipped when entity was deleted after planning`() = runTest {
        val plannedLocal = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val server = TestServer(id = "remote-1", revisionDate = T2)
        val ops = TestEntitySyncOps(emptyList())
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(plannedLocal),
                servers = listOf(server),
                actions = listOf(
                    SyncAction.UpdateLocally(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.UpdateLocally>())
        assertEquals(false, ops.locals.containsKey("local-1"))
    }

    @Test
    fun `local update guard skips entity changed before guarded write`() = runTest {
        val plannedA = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val plannedB = syncedLocal(localId = "local-2", remoteId = "remote-2")
        val serverA = TestServer(id = "remote-1", revisionDate = T2)
        val serverB = TestServer(id = "remote-2", revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(plannedA, plannedB))
        ops.onBeforeUpdateGuard = {
            ops.locals["local-2"] = ops.locals.getValue("local-2").copy(revisionDate = T3)
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(plannedA, plannedB),
                servers = listOf(serverA, serverB),
                actions = listOf(
                    SyncAction.UpdateLocally(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                    SyncAction.UpdateLocally(
                        localId = "local-2",
                        serverId = "remote-2",
                    ),
                ),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(T2, ops.locals.getValue("local-1").revisionDate)
        assertEquals(T3, ops.locals.getValue("local-2").revisionDate)
    }

    @Test
    fun `local update failure records action failure`() = runTest {
        val local = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val server = TestServer(id = "remote-1", revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onUpdateLocally = {
            throw IllegalStateException("db update failed")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(server),
                actions = listOf(
                    SyncAction.UpdateLocally(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.failures.size)
        assertEquals("db update failed", result.failures.single().error.message)
    }

    @Test
    fun `merge conflict failure is tracked and other merges continue`() = runTest {
        val failing = changedLocal(localId = "local-fail", remoteId = "remote-fail", revisionDate = T1)
        val succeeding = changedLocal(localId = "local-success", remoteId = "remote-success", revisionDate = T1)
        val servers = listOf(
            TestServer(id = "remote-fail", revisionDate = T2),
            TestServer(id = "remote-success", revisionDate = T2),
        )
        val ops = TestEntitySyncOps(listOf(failing, succeeding))
        ops.onMergeConflict = { local, server ->
            if (local.id == "local-fail") {
                throw IllegalStateException("merge failed")
            }
            RemoteWriteOutcome.Upsert(
                local.copy(
                    body = "${local.body}|${server.body}",
                    revisionDate = T4,
                    service = testService(remoteId = server.id, remoteRevisionDate = T4),
                ),
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(failing, succeeding),
                servers = servers,
                actions = listOf(
                    SyncAction.MergeConflict(localId = "local-fail", serverId = "remote-fail"),
                    SyncAction.MergeConflict(localId = "local-success", serverId = "remote-success"),
                ),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(1, result.failures.size)
        assertEquals("merge failed", result.failures.single().error.message)
        assertEquals("local-success|remote-success", ops.locals.getValue("local-success").body)
    }

    @Test
    fun `merge conflict upsert saves merged entity locally`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val server = TestServer(id = "remote-1", body = "server body", revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(local))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(server),
                actions = listOf(
                    SyncAction.MergeConflict(localId = "local-1", serverId = "remote-1"),
                ),
            ),
        )

        val saved = ops.locals.getValue("local-1")
        assertEquals(1, result.succeeded)
        assertEquals("local-1|server body", saved.body)
        assertEquals(T4, saved.service.remote?.revisionDate)
    }

    @Test
    fun `merge conflict with missing server snapshot records failure`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            EntitySyncPlan(
                actions = listOf(SyncAction.MergeConflict(localId = "local-1", serverId = "remote-missing")),
                localSnapshot = LocalEntitySnapshot(
                    entitiesByLocalId = mapOf("local-1" to local),
                    metadata = listOf(TestSyncStrategy.toLocalItemMeta(local)),
                ),
                serverSnapshot = ServerEntitySnapshot(
                    entitiesById = emptyMap(),
                    metadata = emptyList(),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().error.message.orEmpty().contains("remote-missing"))
    }

    @Test
    fun `delete on server failure records error metadata on entity`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onDeleteOnServer = { _, _ ->
            throw HttpException(
                statusCode = HttpStatusCode.BadGateway,
                m = "delete failed",
                e = null,
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = emptyList(),
                actions = listOf(
                    SyncAction.DeleteOnServer(localId = "local-1", serverId = "remote-1"),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.failures.size)
        assertEquals(502, ops.locals.getValue("local-1").service.error?.code)
    }

    @Test
    fun `delete on server skips entity modified before remote call`() = runTest {
        val planned = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val modified = planned.copy(revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(modified))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(planned),
                servers = emptyList(),
                actions = listOf(
                    SyncAction.DeleteOnServer(localId = "local-1", serverId = "remote-1"),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.DeleteOnServer>())
        assertEquals(modified, ops.locals.getValue("local-1"))
    }

    @Test
    fun `push to server propagates force flag to ops`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(localId = "local-1", serverId = "remote-1", force = true),
                ),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(
            listOf(SyncCall.PushToServer(localId = "local-1", serverId = "remote-1", force = true)),
            ops.calls.filterIsInstance<SyncCall.PushToServer>(),
        )
    }

    @Test
    fun `push local only item creates remote metadata`() = runTest {
        val local = localOnly(localId = "local-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = emptyList(),
                actions = listOf(
                    SyncAction.PushToServer(localId = "local-1", serverId = null),
                ),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals("remote-local-1", ops.locals.getValue("local-1").service.remote?.id)
    }

    @Test
    fun `remote success finalization preserves newer local edit stores remote metadata and clears stale error`() = runTest {
        val staleError =
            BitwardenService.Error(
                code = 503,
                message = "previous upload failed",
                revisionDate = T0,
            )
        val local =
            changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
                .let { changed ->
                    changed.copy(service = changed.service.copy(error = staleError))
                }
        val server = TestServer(id = "remote-1", revisionDate = T0)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { pushedLocal, pushedServer, _ ->
            ops.locals[pushedLocal.id] = pushedLocal.copy(
                body = "user edit after upload selection",
                revisionDate = T2,
            )
            RemoteWriteOutcome.Upsert(
                pushedLocal.copy(
                    revisionDate = T4,
                    service = testService(
                        remoteId = pushedServer?.id ?: "remote-1",
                        remoteRevisionDate = T4,
                    ),
                ),
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(server),
                actions = listOf(
                    SyncAction.PushToServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        val saved = ops.locals.getValue("local-1")
        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals("user edit after upload selection", saved.body)
        assertEquals(T2, saved.revisionDate)
        assertEquals("remote-1", saved.service.remote?.id)
        assertEquals(T4, saved.service.remote?.revisionDate)
        assertNull(saved.service.error)
    }

    @Test
    fun `remote delete finalization does not resurrect item deleted or edited locally during upload`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onDeleteOnServer = { deletedLocal, _ ->
            ops.locals[deletedLocal.id] = deletedLocal.copy(
                body = "local delete marker after remote call started",
                revisionDate = T2,
            )
            RemoteWriteOutcome.DeleteLocal
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = emptyList(),
                actions = listOf(
                    SyncAction.DeleteOnServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        val saved = ops.locals.getValue("local-1")
        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals("local delete marker after remote call started", saved.body)
        assertNull(saved.service.remote)
        assertNull(saved.service.error)
    }

    @Test
    fun `remote delete success deletes unchanged local entity`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = emptyList(),
                actions = listOf(
                    SyncAction.DeleteOnServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(false, ops.locals.containsKey("local-1"))
    }

    @Test
    fun `remote success save failure is tracked as action failure`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onSaveLocal = {
            throw IllegalStateException("failed to save success")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(localId = "local-1", serverId = "remote-1"),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.failures.size)
        assertEquals("failed to save success", result.failures.single().error.message)
    }

    @Test
    fun `remote delete success skips finalization when local entity was deleted concurrently`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onDeleteOnServer = { deletedLocal, _ ->
            ops.locals.remove(deletedLocal.id)
            RemoteWriteOutcome.DeleteLocal
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = emptyList(),
                actions = listOf(
                    SyncAction.DeleteOnServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(false, ops.locals.containsKey("local-1"))
    }

    @Test
    fun `remote write failure is stored on the item and reported without aborting executor`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { _, _, _ ->
            throw IllegalStateException("server validation failed")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failures.size)
        assertEquals("server validation failed", result.failures.single().error.message)
        assertNotNull(ops.locals.getValue("local-1").service.error)
    }

    @Test
    fun `RemoteWriteOutcome failure cause is used for failure metadata and action failure`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val cause =
            HttpException(
                statusCode = HttpStatusCode.ServiceUnavailable,
                m = "server unavailable",
                e = null,
            )
        ops.onPushToServer = { _, _, _ ->
            RemoteWriteOutcome.Failure(
                partialRemoteLocal = null,
                cause = cause,
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(cause, result.failures.single().error)
        assertEquals(503, ops.locals.getValue("local-1").service.error?.code)
    }

    @Test
    fun `markRemoteFailure extracts ApiException HttpException and generic error codes`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val apiException =
            ApiException(
                title = TextHolder.Value("title"),
                text = null,
                exception = IllegalStateException("api failed"),
                code = HttpStatusCode.BadGateway,
                error = "bad_gateway",
                type = null,
                message = "api failed",
            )
        val httpException =
            HttpException(
                statusCode = HttpStatusCode.Forbidden,
                m = "forbidden",
                e = null,
            )
        val generic = IllegalStateException("generic failure")

        assertEquals(
            502,
            ops.markRemoteFailure(local, remoteLocal = null, error = apiException).service.error?.code,
        )
        assertEquals(
            403,
            ops.markRemoteFailure(local, remoteLocal = null, error = httpException).service.error?.code,
        )
        assertEquals(
            BitwardenService.Error.CODE_UNKNOWN,
            ops.markRemoteFailure(local, remoteLocal = null, error = generic).service.error?.code,
        )
        assertEquals(
            "generic failure",
            ops.markRemoteFailure(local, remoteLocal = null, error = generic).service.error?.message,
        )
        assertTrue(
            ops.markRemoteFailure(local, remoteLocal = null, error = generic).service.error?.revisionDate != T0,
        )
    }

    @Test
    fun `markRemoteFailure prefers localized message and falls back to message`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val localized =
            object : RuntimeException("raw message") {
                override fun getLocalizedMessage(): String = "localized message"
            }
        val fallback =
            object : RuntimeException("raw message") {
                override fun getLocalizedMessage(): String? = null
            }

        assertEquals(
            "localized message",
            ops.markRemoteFailure(local, remoteLocal = null, error = localized).service.error?.message,
        )
        assertEquals(
            "raw message",
            ops.markRemoteFailure(local, remoteLocal = null, error = fallback).service.error?.message,
        )
    }

    @Test
    fun `markRemoteFailure preserves partial remote metadata`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-old", revisionDate = T1)
        val partialRemote =
            local.copy(
                service = testService(
                    remoteId = "remote-new",
                    remoteRevisionDate = T4,
                ),
            )
        val ops = TestEntitySyncOps(listOf(local))

        val failed = ops.markRemoteFailure(
            local = local,
            remoteLocal = partialRemote,
            error = IllegalStateException("upload failed"),
        )

        assertEquals("remote-new", failed.service.remote?.id)
        assertEquals(T4, failed.service.remote?.revisionDate)
        assertEquals(BitwardenService.VERSION, failed.service.version)
        assertNotNull(failed.service.error)
    }

    @Test
    fun `remote write failure finalization failure records one action failure`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { _, _, _ ->
            throw IllegalStateException("server validation failed")
        }
        ops.onSaveLocal = {
            throw IllegalStateException("failed to persist sync error")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failures.size)
        assertEquals(1, result.total)
        assertEquals("server validation failed", result.failures.single().error.message)
    }

    @Test
    fun `cancellation during remote write propagates`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { _, _, _ ->
            throw CancellationException("remote write cancelled")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = listOf(local),
                    servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                    actions = listOf(
                        SyncAction.PushToServer(
                            localId = "local-1",
                            serverId = "remote-1",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `cancellation during success finalization propagates`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onSaveLocal = {
            throw CancellationException("save cancelled")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = listOf(local),
                    servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                    actions = listOf(
                        SyncAction.PushToServer(
                            localId = "local-1",
                            serverId = "remote-1",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `cancellation during failure finalization propagates`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { _, _, _ ->
            throw IllegalStateException("server validation failed")
        }
        ops.onSaveLocal = {
            throw CancellationException("save failure metadata cancelled")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = listOf(local),
                    servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                    actions = listOf(
                        SyncAction.PushToServer(
                            localId = "local-1",
                            serverId = "remote-1",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `partial remote failure keeps reservation metadata for retry`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val partialRemote = local.copy(
            revisionDate = T4,
            service = testService(
                remoteId = "reserved-remote-1",
                remoteRevisionDate = T4,
            ),
        )
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { _, _, _ ->
            RemoteWriteOutcome.Failure(
                partialRemoteLocal = partialRemote,
                cause = IllegalStateException("bytes upload failed"),
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        val saved = ops.locals.getValue("local-1")
        assertEquals(1, result.failures.size)
        assertEquals("reserved-remote-1", saved.service.remote?.id)
        assertEquals(T4, saved.service.remote?.revisionDate)
        assertNotNull(saved.service.error)
    }

    @Test
    fun `partial remote failure during concurrent edit preserves edit and stores partial remote metadata`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val partialRemote = local.copy(
            revisionDate = T4,
            service = testService(
                remoteId = "reserved-remote-1",
                remoteRevisionDate = T4,
            ),
        )
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { pushedLocal, _, _ ->
            ops.locals[pushedLocal.id] = pushedLocal.copy(
                body = "local edit after partial remote write",
                revisionDate = T2,
            )
            RemoteWriteOutcome.Failure(
                partialRemoteLocal = partialRemote,
                cause = IllegalStateException("bytes upload failed"),
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(
                        localId = "local-1",
                        serverId = "remote-1",
                    ),
                ),
            ),
        )

        val saved = ops.locals.getValue("local-1")
        assertEquals(1, result.failures.size)
        assertEquals("local edit after partial remote write", saved.body)
        assertEquals(T2, saved.revisionDate)
        assertEquals("reserved-remote-1", saved.service.remote?.id)
        assertEquals(T4, saved.service.remote?.revisionDate)
        val error = assertNotNull(saved.service.error)
        assertEquals(BitwardenService.Error.CODE_UNKNOWN, error.code)
        assertEquals("bytes upload failed", error.message)
    }

    @Test
    fun `remote write failure during concurrent edit without partial remote keeps edit without saving failure metadata`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { pushedLocal, _, _ ->
            ops.locals[pushedLocal.id] = pushedLocal.copy(
                body = "local edit during failed upload",
                revisionDate = T2,
            )
            RemoteWriteOutcome.Failure(
                partialRemoteLocal = null,
                cause = IllegalStateException("upload failed"),
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(localId = "local-1", serverId = "remote-1"),
                ),
            ),
        )

        val saved = ops.locals.getValue("local-1")
        assertEquals(1, result.failures.size)
        assertEquals("local edit during failed upload", saved.body)
        assertEquals(T2, saved.revisionDate)
        assertNull(saved.service.error)
    }

    @Test
    fun `remote write failure after concurrent delete records failure without saving metadata`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { pushedLocal, _, _ ->
            ops.locals.remove(pushedLocal.id)
            throw IllegalStateException("upload failed")
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(localId = "local-1", serverId = "remote-1"),
                ),
            ),
        )

        assertEquals(1, result.failures.size)
        assertEquals(false, ops.locals.containsKey("local-1"))
        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.SaveLocal>())
    }

    @Test
    fun `remote write exception without partial remote preserves existing remote metadata on failure`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { _, _, _ ->
            RemoteWriteOutcome.Failure(
                partialRemoteLocal = null,
                cause = IllegalStateException("upload failed"),
            )
        }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops)

        val result = executor.execute(
            plan(
                locals = listOf(local),
                servers = listOf(TestServer(id = "remote-1", revisionDate = T0)),
                actions = listOf(
                    SyncAction.PushToServer(localId = "local-1", serverId = "remote-1"),
                ),
            ),
        )

        val saved = ops.locals.getValue("local-1")
        assertEquals(1, result.failures.size)
        assertEquals("remote-1", saved.service.remote?.id)
        assertEquals(T0, saved.service.remote?.revisionDate)
        assertNotNull(saved.service.error)
    }

    @Test
    fun `bulk delete uses configured chunk size and deletes unchanged local rows`() = runTest {
        val count = EntitySyncExecutor.BULK_CHUNK_SIZE + 1
        val locals = (1..count).map { index ->
            changedLocal(
                localId = "local-$index",
                remoteId = "remote-$index",
                revisionDate = T1,
            )
        }
        val actions = (1..count).map { index ->
            SyncAction.DeleteOnServer(
                localId = "local-$index",
                serverId = "remote-$index",
            )
        }
        val ops = TestEntitySyncOps(locals)
        val bulkOps = TestBulkRemoteOps()
        val executor = EntitySyncExecutor(TestSyncStrategy, ops, bulkOps)

        val result = executor.execute(
            plan(
                locals = locals,
                servers = emptyList(),
                actions = actions,
            ),
        )

        assertEquals(count, result.succeeded)
        assertEquals(
            listOf(EntitySyncExecutor.BULK_CHUNK_SIZE, 1),
            bulkOps.chunks.map { it.size },
        )
        assertEquals(
            emptyMap(),
            ops.locals,
        )
    }

    @Test
    fun `bulk delete failure falls back to individual delete calls`() = runTest {
        val locals = listOf(
            changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1),
            changedLocal(localId = "local-2", remoteId = "remote-2", revisionDate = T1),
        )
        val actions = listOf(
            SyncAction.DeleteOnServer(localId = "local-1", serverId = "remote-1"),
            SyncAction.DeleteOnServer(localId = "local-2", serverId = "remote-2"),
        )
        val ops = TestEntitySyncOps(locals)
        val bulkOps = TestBulkRemoteOps(fail = true)
        val executor = EntitySyncExecutor(TestSyncStrategy, ops, bulkOps)

        val result = executor.execute(
            plan(
                locals = locals,
                servers = emptyList(),
                actions = actions,
            ),
        )

        assertEquals(2, result.succeeded)
        assertEquals(listOf(2), bulkOps.chunks.map { it.size })
        assertEquals(
            listOf(
                SyncCall.DeleteOnServer(localId = "local-1", serverId = "remote-1"),
                SyncCall.DeleteOnServer(localId = "local-2", serverId = "remote-2"),
            ),
            ops.calls.filterIsInstance<SyncCall.DeleteOnServer>(),
        )
    }

    @Test
    fun `bulk delete sends only entries that pass concurrency checks`() = runTest {
        val plannedSafe = changedLocal(localId = "local-safe", remoteId = "remote-safe", revisionDate = T1)
        val plannedModified = changedLocal(localId = "local-modified", remoteId = "remote-modified", revisionDate = T1)
        val modifiedCurrent = plannedModified.copy(revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(plannedSafe, modifiedCurrent))
        val bulkOps = TestBulkRemoteOps()
        val executor = EntitySyncExecutor(TestSyncStrategy, ops, bulkOps)

        val result = executor.execute(
            plan(
                locals = listOf(plannedSafe, plannedModified),
                servers = emptyList(),
                actions = listOf(
                    SyncAction.DeleteOnServer(localId = "local-safe", serverId = "remote-safe"),
                    SyncAction.DeleteOnServer(localId = "local-modified", serverId = "remote-modified"),
                ),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(listOf(listOf(plannedSafe to "remote-safe")), bulkOps.chunks)
        assertEquals(false, ops.locals.containsKey("local-safe"))
        assertEquals("remote-modified", ops.locals.getValue("local-modified").service.remote?.id)
    }

    @Test
    fun `bulk delete fallback records per action failures when individual deletes also fail`() = runTest {
        val locals = listOf(
            changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1),
            changedLocal(localId = "local-2", remoteId = "remote-2", revisionDate = T1),
        )
        val ops = TestEntitySyncOps(locals)
        ops.onDeleteOnServer = { _, serverId ->
            throw IllegalStateException("individual delete failed for $serverId")
        }
        val bulkOps = TestBulkRemoteOps(fail = true)
        val executor = EntitySyncExecutor(TestSyncStrategy, ops, bulkOps)

        val result = executor.execute(
            plan(
                locals = locals,
                servers = emptyList(),
                actions = listOf(
                    SyncAction.DeleteOnServer(localId = "local-1", serverId = "remote-1"),
                    SyncAction.DeleteOnServer(localId = "local-2", serverId = "remote-2"),
                ),
            ),
        )

        assertEquals(0, result.succeeded)
        assertEquals(2, result.failures.size)
        assertEquals(
            listOf("individual delete failed for remote-1", "individual delete failed for remote-2"),
            result.failures.map { it.error.message },
        )
    }

    @Test
    fun `bulk delete cancellation propagates without individual fallback`() = runTest {
        val local = changedLocal(localId = "local-1", remoteId = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val bulkOps =
            object : com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.BulkRemoteOps<TestLocal> {
                override suspend fun bulkDeleteOnServer(entries: List<Pair<TestLocal, String>>) {
                    throw CancellationException("bulk cancelled")
                }

                override suspend fun bulkRestoreOnServer(serverIds: List<String>) {
                    error("unused")
                }

                override suspend fun bulkTrashOnServer(serverIds: List<String>) {
                    error("unused")
                }
            }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops, bulkOps)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = listOf(local),
                    servers = emptyList(),
                    actions = listOf(
                        SyncAction.DeleteOnServer(localId = "local-1", serverId = "remote-1"),
                    ),
                ),
            )
        }

        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.DeleteOnServer>())
    }

    @Test
    fun `cancellation inside bulk chunk loop propagates without fallback`() = runTest {
        val count = EntitySyncExecutor.BULK_CHUNK_SIZE + 1
        val locals = (1..count).map { index ->
            changedLocal(localId = "local-$index", remoteId = "remote-$index", revisionDate = T1)
        }
        val ops = TestEntitySyncOps(locals)
        val chunks = mutableListOf<List<Pair<TestLocal, String>>>()
        val bulkOps =
            object : com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.BulkRemoteOps<TestLocal> {
                override suspend fun bulkDeleteOnServer(entries: List<Pair<TestLocal, String>>) {
                    chunks += entries
                    if (chunks.size == 2) {
                        throw CancellationException("cancelled in bulk chunk loop")
                    }
                }

                override suspend fun bulkRestoreOnServer(serverIds: List<String>) {
                    error("unused")
                }

                override suspend fun bulkTrashOnServer(serverIds: List<String>) {
                    error("unused")
                }
            }
        val executor = EntitySyncExecutor(TestSyncStrategy, ops, bulkOps)

        kotlin.test.assertFailsWith<CancellationException> {
            executor.execute(
                plan(
                    locals = locals,
                    servers = emptyList(),
                    actions = locals.mapIndexed { index, local ->
                        SyncAction.DeleteOnServer(localId = local.id, serverId = "remote-${index + 1}")
                    },
                ),
            )
        }

        assertEquals(listOf(EntitySyncExecutor.BULK_CHUNK_SIZE, 1), chunks.map { it.size })
        assertEquals(emptyList(), ops.calls.filterIsInstance<SyncCall.DeleteOnServer>())
    }

    private fun plan(
        locals: List<TestLocal>,
        servers: List<TestServer>,
        actions: List<SyncAction>,
    ): EntitySyncPlan<TestLocal, TestServer> =
        EntitySyncPlan(
            actions = actions,
            localSnapshot =
                LocalEntitySnapshot(
                    entitiesByLocalId = locals.associateBy { it.id },
                    metadata = locals.map(TestSyncStrategy::toLocalItemMeta),
                ),
            serverSnapshot =
                ServerEntitySnapshot(
                    entitiesById = servers.associateBy { it.id },
                    metadata = servers.map(TestSyncStrategy::toServerItemMeta),
                ),
        )
}
