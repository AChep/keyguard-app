package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.defaultVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightRole
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightSpan
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlighting
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.VaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledHotTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParsedQuery
import com.artemchep.keyguard.feature.search.search.SEARCH_DEBOUNCE
import com.artemchep.keyguard.feature.search.search.SEARCH_DEBOUNCE_LONG
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultSearchQueryHandleFlowsTest {
    @Test
    fun `query highlighting uses the supplied search mode`() {
        val highlighter = RecordingQueryHighlighter()

        val highlighting = vaultSearchQueryHighlighting(
            query = "secret",
            searchBy = VaultRoute.Args.SearchBy.PASSWORD,
            queryHighlighter = highlighter,
            qualifierCatalog = defaultVaultSearchQualifierCatalog,
        )

        assertEquals(
            QueryHighlighting(
                spans = listOf(
                    QueryHighlightSpan(
                        start = 0,
                        end = 6,
                        role = QueryHighlightRole.TextClause,
                    ),
                ),
            ),
            highlighting,
        )
        assertEquals(
            listOf("secret" to VaultRoute.Args.SearchBy.PASSWORD),
            highlighter.calls,
        )
    }

    @Test
    fun `blank query emits null immediately`() = runTest {
        val result = flowOf("" to "")
            .vaultSearchDebouncedQueryFlow()
            .first()

        assertNull(result)
    }

    @Test
    fun `short query uses long debounce`() = runTest {
        val queryFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
        val values = mutableListOf<String?>()
        val job = launch {
            queryFlow
                .vaultSearchDebouncedQueryFlow()
                .collect(values::add)
        }

        runCurrent()
        assertTrue(queryFlow.tryEmit("ab" to "ab"))
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE_LONG - 1)
        assertEquals(emptyList(), values)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<String?>("ab"), values)
        job.cancel()
    }

    @Test
    fun `long query uses short debounce`() = runTest {
        val queryFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
        val values = mutableListOf<String?>()
        val job = launch {
            queryFlow
                .vaultSearchDebouncedQueryFlow()
                .collect(values::add)
        }

        runCurrent()
        assertTrue(queryFlow.tryEmit("abcd" to "abcd"))
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE - 1)
        assertEquals(emptyList(), values)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<String?>("abcd"), values)
        job.cancel()
    }

    @Test
    fun `empty query bypasses the search index`() = runTest {
        val searchIndexFlow = MutableSharedFlow<TestVaultSearchIndex>()

        val result = flowOf<String?>(null)
            .vaultSearchContextFlow(
                searchIndexFlow = searchIndexFlow,
                qualifierCatalogFlow = flowOf(defaultVaultSearchQualifierCatalog),
                searchBy = VaultRoute.Args.SearchBy.ALL,
            )
            .first()

        assertNull(result)
        assertEquals(0, searchIndexFlow.subscriptionCount.value)
    }

    @Test
    fun `non-empty query subscribes and compiles once the index is available`() = runTest {
        val searchIndexFlow = MutableSharedFlow<TestVaultSearchIndex>()
        val index = TestVaultSearchIndex()

        val result = async {
            flowOf("alice")
                .vaultSearchContextFlow(
                    searchIndexFlow = searchIndexFlow,
                    qualifierCatalogFlow = flowOf(defaultVaultSearchQualifierCatalog),
                    searchBy = VaultRoute.Args.SearchBy.ALL,
                )
                .first()
        }

        advanceUntilIdle()
        assertEquals(1, searchIndexFlow.subscriptionCount.value)
        assertFalse(result.isCompleted)

        searchIndexFlow.emit(index)

        val context = result.await()
        assertNotNull(context)
        assertEquals("alice", context.queryPlan?.rawQuery)
        assertEquals(listOf("alice"), index.compileQueries)
    }

    @Test
    fun `compile failure keeps context but resets revision to zero`() = runTest {
        val searchIndex = TestVaultSearchIndex(
            compilePlan = null,
        )

        val revision = flowOf("alice")
            .vaultSearchContextFlow(
                searchIndexFlow = flowOf<VaultSearchIndex>(searchIndex),
                qualifierCatalogFlow = flowOf(defaultVaultSearchQualifierCatalog),
                searchBy = VaultRoute.Args.SearchBy.ALL,
            )
            .map { it?.queryPlan?.id ?: 0 }
            .first()

        assertEquals(0, revision)
        assertEquals(listOf("alice"), searchIndex.compileQueries)
    }
}

private class RecordingQueryHighlighter : VaultSearchQueryHighlighter {
    val calls = mutableListOf<Pair<String, VaultRoute.Args.SearchBy>>()

    override fun highlight(
        query: String,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): QueryHighlighting {
        calls += query to searchBy
        return QueryHighlighting(
            spans = listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = query.length,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
        )
    }
}

private class TestVaultSearchIndex(
    private val compilePlan: CompiledQueryPlan? = fakeQueryPlan("alice"),
) : VaultSearchIndex {
    val compileQueries = mutableListOf<String>()

    override fun compile(
        query: String,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): CompiledQueryPlan? {
        compileQueries += query
        return compilePlan?.copy(rawQuery = query)
    }

    override suspend fun evaluate(
        plan: CompiledQueryPlan?,
        candidates: List<com.artemchep.keyguard.feature.home.vault.model.VaultItem2.Item>,
        highlightBackgroundColor: androidx.compose.ui.graphics.Color,
        highlightContentColor: androidx.compose.ui.graphics.Color,
    ): List<com.artemchep.keyguard.feature.home.vault.model.VaultItem2.Item> = candidates
}

private fun fakeQueryPlan(query: String): CompiledQueryPlan = CompiledQueryPlan(
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
