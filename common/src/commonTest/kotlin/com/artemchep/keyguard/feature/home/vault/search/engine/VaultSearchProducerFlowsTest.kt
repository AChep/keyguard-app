package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.createItem
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledHotTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParsedQuery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VaultSearchProducerFlowsTest {
    @Test
    fun `filtered items bypass evaluation when query plan is null`() = runTest {
        val item = createItem(
            createSecret(
                id = "alpha",
                name = "Alpha",
            ),
        )
        val searchIndex = RecordingVaultSearchIndex()

        val result = vaultSearchFilteredItemsFlow(
            itemsFlow = flowOf(listOf(item)),
            searchContextFlow = flowOf(
                VaultSearchContext(
                    searchIndex = searchIndex,
                    queryPlan = null,
                ),
            ),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        ).first()

        assertEquals(listOf(item), result)
        assertEquals(0, searchIndex.evaluateCalls)
    }

    @Test
    fun `filtered items evaluate through search index when query plan exists`() = runTest {
        val item = createItem(
            createSecret(
                id = "alpha",
                name = "Alpha",
            ),
        )
        val queryPlan = fakeScoringQueryPlan("alice")
        val searchIndex = RecordingVaultSearchIndex()

        val result = vaultSearchFilteredItemsFlow(
            itemsFlow = flowOf(listOf(item)),
            searchContextFlow = flowOf(
                VaultSearchContext(
                    searchIndex = searchIndex,
                    queryPlan = queryPlan,
                ),
            ),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        ).first()

        assertEquals(listOf(item), result)
        assertEquals(1, searchIndex.evaluateCalls)
        assertSame(queryPlan, searchIndex.lastEvaluatedPlan)
        assertEquals(listOf(item), searchIndex.lastCandidates)
    }

    @Test
    fun `trace flow emits provided metrics and scoring ranking mode`() = runTest {
        val event = vaultSearchTraceFlow(
            surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
            debouncedQueryFlow = flowOf("alice"),
            searchContextFlow = flowOf(
                VaultSearchContext(
                    searchIndex = RecordingVaultSearchIndex(),
                    queryPlan = fakeScoringQueryPlan("alice"),
                ),
            ),
            rawItemCountFlow = flowOf(10),
            routeFilteredCountFlow = flowOf(8),
            filterFilteredCountFlow = flowOf(5),
            preferredCountFlow = flowOf(2),
            activeSortFlow = flowOf("alphabetical[favorites]"),
            finalResultCountFlow = flowOf(3),
        ).first()

        assertEquals(
            SurfaceTraceEvent(
                surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
                rawQuery = "alice",
                rawItemCount = 10,
                routeFilteredCount = 8,
                filterFilteredCount = 5,
                preferredCount = 2,
                activeSort = "alphabetical[favorites]",
                rankingMode = "query-score-first",
                finalResultCount = 3,
            ),
            event,
        )
    }

    @Test
    fun `trace flow emits sort preserving mode when plan is absent`() = runTest {
        val event = vaultSearchTraceFlow(
            surface = VAULT_SEARCH_SURFACE_QUICK_SEARCH,
            debouncedQueryFlow = flowOf("alice"),
            searchContextFlow = flowOf(
                VaultSearchContext(
                    searchIndex = RecordingVaultSearchIndex(),
                    queryPlan = null,
                ),
            ),
            activeSortFlow = flowOf("alphabetical[favorites]"),
            finalResultCountFlow = flowOf(3),
        ).first()

        assertEquals("sort-preserving", event?.rankingMode)
        assertNull(event?.rawItemCount)
    }
}

private class RecordingVaultSearchIndex : VaultSearchIndex {
    var evaluateCalls = 0
    var lastEvaluatedPlan: CompiledQueryPlan? = null
    var lastCandidates: List<VaultItem2.Item> = emptyList()

    override fun compile(
        query: String,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): CompiledQueryPlan? = null

    override suspend fun evaluate(
        plan: CompiledQueryPlan?,
        candidates: List<VaultItem2.Item>,
        highlightBackgroundColor: Color,
        highlightContentColor: Color,
    ): List<VaultItem2.Item> {
        evaluateCalls += 1
        lastEvaluatedPlan = plan
        lastCandidates = candidates
        return candidates
    }
}

private fun fakeScoringQueryPlan(query: String): CompiledQueryPlan = CompiledQueryPlan(
    rawQuery = query,
    parsedQuery = ParsedQuery(
        source = query,
        clauses = emptyList(),
    ),
    positiveClauses = listOf(
        CompiledHotTextClause(
            fields = setOf(VaultTextField.Title),
            tokenizations = mapOf(
                SearchTokenizerProfile.TEXT to SearchTokenization(
                    normalizedText = query,
                    terms = listOf(query),
                ),
            ),
            raw = query,
        ),
    ),
    negativeClauses = emptyList(),
)
