package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncConfig
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncExecutor
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.SyncCoordinator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EntitySyncExecutorDiagnosticsV2Test {
    @Test
    fun `coordinator emits entity plan diagnostics`() = runTest {
        val logRepository = TestLogRepository()
        val diagnostics =
            SyncDiagnostics(
                logRepository = logRepository,
                enabled = true,
            )
        val local = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val server = TestServer(id = "remote-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))

        val outcome =
            SyncCoordinator(diagnostics).safeSyncEntityType(
                EntitySyncConfig(
                    name = "test",
                    strategy = TestSyncStrategy,
                    localEntities = listOf(local),
                    serverEntities = listOf(server),
                    ops = ops,
                ),
            )

        assertIs<EntityTypeOutcome.Completed>(outcome)
        assertTrue(logRepository.messages.any { it.contains("entity_snapshot entity=test") })
        assertTrue(logRepository.messages.any { it.contains("entity_plan_built entity=test") })
        assertTrue(logRepository.messages.any { it.contains("update_locally=1") })
    }

    @Test
    fun `executor emits phase and occ skip diagnostics`() = runTest {
        val logRepository = TestLogRepository()
        val diagnostics =
            SyncDiagnostics(
                logRepository = logRepository,
                enabled = true,
            )
        val plannedLocal = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val concurrentlyEditedLocal = plannedLocal.copy(revisionDate = T1)
        val server = TestServer(id = "remote-1", revisionDate = T2)
        val ops = TestEntitySyncOps(listOf(concurrentlyEditedLocal))
        val executor =
            EntitySyncExecutor(
                strategy = TestSyncStrategy,
                ops = ops,
                entityName = "test",
                diagnostics = diagnostics,
            )

        executor.execute(
            plan(
                locals = listOf(plannedLocal),
                servers = listOf(server),
                actions =
                    listOf(
                        SyncAction.UpdateLocally(
                            localId = "local-1",
                            serverId = "remote-1",
                        ),
                    ),
            ),
        )

        assertTrue(logRepository.messages.any { it.contains("executor_phase_started entity=test") })
        assertTrue(logRepository.messages.any { it.contains("executor_phase_completed entity=test") })
        assertTrue(
            logRepository.messages.any {
                it.contains("occ_skipped entity=test") &&
                    it.contains("local_id=local-1") &&
                    it.contains("remote_id=remote-1")
            },
        )
    }

    @Test
    fun `executor emits bulk fallback diagnostics`() = runTest {
        val logRepository = TestLogRepository()
        val diagnostics =
            SyncDiagnostics(
                logRepository = logRepository,
                enabled = true,
            )
        val local = changedLocal(localId = "local-1", remoteId = "remote-1")
        val ops = TestEntitySyncOps(listOf(local))
        val bulkOps = TestBulkRemoteOps(fail = true)
        val executor =
            EntitySyncExecutor(
                strategy = TestSyncStrategy,
                ops = ops,
                bulkRemoteOps = bulkOps,
                entityName = "test",
                diagnostics = diagnostics,
            )

        executor.execute(
            plan(
                locals = listOf(local),
                servers = emptyList(),
                actions =
                    listOf(
                        SyncAction.DeleteOnServer(
                            localId = "local-1",
                            serverId = "remote-1",
                        ),
                    ),
            ),
        )

        assertTrue(logRepository.messages.any { it.contains("bulk_delete_chunk entity=test") })
        assertTrue(logRepository.messages.any { it.contains("bulk_fallback entity=test") })
    }
}

private val TestLogRepository.messages: List<String>
    get() = entries.map { it.message }

private fun plan(
    locals: List<TestLocal>,
    servers: List<TestServer>,
    actions: List<SyncAction>,
) =
    com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan(
        actions = actions,
        localSnapshot =
            com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalEntitySnapshot(
                entitiesByLocalId = locals.associateBy { it.id },
                metadata = locals.map(TestSyncStrategy::toLocalItemMeta),
            ),
        serverSnapshot =
            com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerEntitySnapshot(
                entitiesById = servers.associateBy { it.id },
                metadata = servers.map(TestSyncStrategy::toServerItemMeta),
            ),
    )
