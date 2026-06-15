package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchExecutor
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultVaultSearchIndexBuilder
import com.artemchep.keyguard.feature.home.vault.search.engine.EvaluationTraceEvent
import com.artemchep.keyguard.feature.home.vault.search.engine.IndexTraceEvent
import com.artemchep.keyguard.feature.home.vault.search.engine.IndexTracePhase
import com.artemchep.keyguard.feature.home.vault.search.engine.ItemTraceEvent
import com.artemchep.keyguard.feature.home.vault.search.engine.QueryTraceEvent
import com.artemchep.keyguard.feature.home.vault.search.engine.SurfaceTraceEvent
import com.artemchep.keyguard.feature.home.vault.search.engine.VAULT_SEARCH_SURFACE_QUICK_SEARCH
import com.artemchep.keyguard.feature.home.vault.search.engine.VAULT_SEARCH_SURFACE_VAULT_LIST
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchTraceSink
import com.artemchep.keyguard.feature.home.vault.search.engine.Bm25SearchScorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GetVaultSearchIndexImplTest {
    @Test
    fun `shared corpus is built once for multiple surfaces`() = runTest {
        val traceSink = SharedCorpusRecordingVaultSearchTraceSink()
        val tokenizer = DefaultSearchTokenizer()
        val builder = DefaultVaultSearchIndexBuilder(
            tokenizer = tokenizer,
            scorer = Bm25SearchScorer(),
            executor = DefaultSearchExecutor(),
            parser = DefaultVaultSearchParser(),
            compiler = DefaultVaultSearchQueryCompiler(tokenizer),
            traceSink = traceSink,
        )
        val ciphersFlow = MutableStateFlow(
            listOf(
                createSecret(
                    id = "alpha",
                    name = "Alpha Account",
                    login = DSecret.Login(username = "alice@example.com"),
                ),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val useCase = GetVaultSearchIndexImpl(
            logRepository = NoOpLogRepository,
            getCiphers = flowUseCase(ciphersFlow),
            getAccounts = flowUseCase(MutableStateFlow<List<DAccount>>(emptyList())),
            getFolders = flowUseCase(MutableStateFlow<List<DFolder>>(emptyList())),
            getTags = flowUseCase(MutableStateFlow<List<DTag>>(emptyList())),
            getCollections = flowUseCase(MutableStateFlow<List<DCollection>>(emptyList())),
            getOrganizations = flowUseCase(MutableStateFlow<List<DOrganization>>(emptyList())),
            searchIndexBuilder = builder,
            windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by backgroundScope {},
            dispatcher = dispatcher,
        )

        val vaultIndex = async { useCase(VAULT_SEARCH_SURFACE_VAULT_LIST).first() }
        val quickIndex = async { useCase(VAULT_SEARCH_SURFACE_QUICK_SEARCH).first() }
        val query = "alpha"
        val vaultPlan = vaultIndex.await().compile(query, VaultRoute.Args.SearchBy.ALL)
        val quickPlan = quickIndex.await().compile(query, VaultRoute.Args.SearchBy.ALL)

        assertNotNull(vaultPlan)
        assertNotNull(quickPlan)
        assertEquals(
            1,
            traceSink.indexEvents.count { it.phase == IndexTracePhase.Finish },
        )
        assertEquals(
            listOf(VAULT_SEARCH_SURFACE_VAULT_LIST, VAULT_SEARCH_SURFACE_QUICK_SEARCH),
            traceSink.queryEvents.map(QueryTraceEvent::surface),
        )
    }
}

private fun flowUseCase(
    flow: Flow<List<DSecret>>,
): GetCiphers = object : GetCiphers {
    override fun invoke(): Flow<List<DSecret>> = flow
}

private fun flowUseCase(
    flow: Flow<List<DAccount>>,
): GetAccounts = object : GetAccounts {
    override fun invoke(): Flow<List<DAccount>> = flow
}

private fun flowUseCase(
    flow: Flow<List<DFolder>>,
): GetFolders = object : GetFolders {
    override fun invoke(): Flow<List<DFolder>> = flow
}

private fun flowUseCase(
    flow: Flow<List<DTag>>,
): GetTags = object : GetTags {
    override fun invoke(): Flow<List<DTag>> = flow
}

private fun flowUseCase(
    flow: Flow<List<DCollection>>,
): GetCollections = object : GetCollections {
    override fun invoke(): Flow<List<DCollection>> = flow
}

private fun flowUseCase(
    flow: Flow<List<DOrganization>>,
): GetOrganizations = object : GetOrganizations {
    override fun invoke(): Flow<List<DOrganization>> = flow
}

private class SharedCorpusRecordingVaultSearchTraceSink : VaultSearchTraceSink {
    override val isEnabled: Boolean = true

    val indexEvents = mutableListOf<IndexTraceEvent>()
    val queryEvents = mutableListOf<QueryTraceEvent>()

    override fun index(event: IndexTraceEvent) {
        indexEvents += event
    }

    override fun query(event: QueryTraceEvent) {
        queryEvents += event
    }

    override fun evaluation(event: EvaluationTraceEvent) = Unit

    override fun item(event: ItemTraceEvent) = Unit

    override fun surface(event: SurfaceTraceEvent) = Unit
}
