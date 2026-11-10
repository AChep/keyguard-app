package com.artemchep.keyguard.common.service.urlblock.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.service.database.exposed.TestExposedDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepository
import com.artemchep.keyguard.dataexposed.UrlBlock as ExposedUrlBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class UrlBlockExposedSyncerImplTest {
    @Test
    fun `only exposed rules reach the exposed database under their vault ids`() = runTest {
        val exposedDbManager = TestExposedDatabaseManager()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rules = MutableStateFlow(
            listOf(
                createRule(
                    id = "1",
                    name = "Exposed",
                    description = "Blocks the bank",
                    uri = "^https://bank\\.example\\.com/.*$",
                    mode = MatchDetection.RegularExpression,
                    enabled = false,
                    exposed = true,
                ),
                createRule(
                    id = "2",
                    name = "Vault only",
                    exposed = false,
                ),
            ),
        )
        val syncer = createSyncer(
            rules = rules,
            exposedDbManager = exposedDbManager,
            dispatcher = dispatcher,
        )

        val job = syncer.launch(this)
        try {
            runCurrent()

            val row = exposedRows(exposedDbManager).single()
            assertEquals(1L, row.id)
            assertEquals("Exposed", row.name)
            assertEquals("Blocks the bank", row.description)
            assertEquals("^https://bank\\.example\\.com/.*$", row.uri)
            assertEquals(MatchDetection.RegularExpression.key, row.mode)
            assertEquals(CREATED_AT, row.createdAt)
            assertEquals(0L, row.enabled)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `a rule that leaves the exposed rules loses its copy`() = runTest {
        val exposedDbManager = TestExposedDatabaseManager()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val kept = createRule(id = "1", name = "Kept", exposed = true)
        val deleted = createRule(id = "2", name = "Deleted", exposed = true)
        val hidden = createRule(id = "3", name = "Hidden", exposed = true)
        val rules = MutableStateFlow(listOf(kept, deleted, hidden))
        val syncer = createSyncer(
            rules = rules,
            exposedDbManager = exposedDbManager,
            dispatcher = dispatcher,
        )

        val job = syncer.launch(this)
        try {
            runCurrent()
            assertEquals(listOf(1L, 2L, 3L), exposedRows(exposedDbManager).map { it.id })

            rules.value = listOf(
                kept.copy(name = "Renamed"),
                hidden.copy(exposed = false),
            )
            runCurrent()

            val row = exposedRows(exposedDbManager).single()
            assertEquals(1L, row.id)
            assertEquals("Renamed", row.name)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `the first state removes a stale copy`() = runTest {
        val exposedDbManager = TestExposedDatabaseManager()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exposedRepository = UrlBlockRepositoryExposed(
            exposedDatabaseManager = exposedDbManager,
            dispatcher = dispatcher,
        )
        exposedRepository
            .replaceAll(listOf(createRule(id = "9", name = "Stale", exposed = true)))
            .bind()
        val syncer = createSyncer(
            rules = MutableStateFlow(listOf(createRule(id = "1", name = "Fresh", exposed = true))),
            exposedDbManager = exposedDbManager,
            dispatcher = dispatcher,
        )

        val job = syncer.launch(this)
        try {
            runCurrent()

            assertEquals(listOf(1L), exposedRows(exposedDbManager).map { it.id })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `replace all skips a rule without a local id`() = runTest {
        val exposedDbManager = TestExposedDatabaseManager()
        val exposedRepository = UrlBlockRepositoryExposed(
            exposedDatabaseManager = exposedDbManager,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        exposedRepository
            .replaceAll(
                listOf(
                    createRule(id = null, name = "New", exposed = true),
                    createRule(id = "not-a-number", name = "Foreign", exposed = true),
                    createRule(id = "5", name = "Local", exposed = true),
                ),
            )
            .bind()

        assertEquals(listOf(5L), exposedRows(exposedDbManager).map { it.id })
    }

    private fun createSyncer(
        rules: Flow<List<DGlobalUrlBlock>>,
        exposedDbManager: TestExposedDatabaseManager,
        dispatcher: CoroutineDispatcher,
    ) = UrlBlockExposedSyncerImpl(
        urlBlockRepository = FlowUrlBlockRepository(rules),
        urlBlockRepositoryExposed = UrlBlockRepositoryExposed(
            exposedDatabaseManager = exposedDbManager,
            dispatcher = dispatcher,
        ),
        logRepository = LogRepositoryBridge(emptyList()),
    )

    private fun exposedRows(
        exposedDbManager: TestExposedDatabaseManager,
    ): List<ExposedUrlBlock> = exposedDbManager.database.urlBlockQueries
        .get(1000)
        .executeAsList()
        .sortedBy { it.id }

    private fun createRule(
        id: String?,
        name: String,
        exposed: Boolean,
        description: String = "",
        uri: String = "example.com",
        mode: MatchDetection = MatchDetection.Domain,
        enabled: Boolean = true,
    ) = DGlobalUrlBlock(
        id = id,
        name = name,
        description = description,
        uri = uri,
        mode = mode,
        createdDate = CREATED_AT,
        enabled = enabled,
        exposed = exposed,
    )

    /** The vault rules, as the [rules] flow emits them. */
    private class FlowUrlBlockRepository(
        private val rules: Flow<List<DGlobalUrlBlock>>,
    ) : UrlBlockRepository {
        override fun get(): Flow<List<DGlobalUrlBlock>> = rules

        override fun put(model: DGlobalUrlBlock): IO<Unit> = error("Not used")

        override fun removeAll(): IO<Unit> = error("Not used")

        override fun removeByIds(ids: Set<String>): IO<Unit> = error("Not used")
    }

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }
}
