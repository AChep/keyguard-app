package com.artemchep.keyguard.feature.home.vault.search.benchmark

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.feature.home.vault.search.engine.Bm25SearchScorer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchExecutor
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultVaultSearchIndexBuilder
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndex
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryPlan
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VaultSearchBenchmarkTest {
    private val tokenizer = DefaultSearchTokenizer()
    private val parser = DefaultVaultSearchParser()
    private val compiler = DefaultVaultSearchQueryCompiler(tokenizer)
    private val scorer = Bm25SearchScorer()
    private val executor = DefaultSearchExecutor()
    private val harness = VaultSearchBenchmarkHarness()
    private val corpora = VaultSearchBenchmarkFixtures.buildCorpora()
    private val queries = VaultSearchBenchmarkFixtures.buildQueries()

    @Test
    fun `fixtures are deterministic`() = runTest {
        val first = VaultSearchBenchmarkFixtures.buildCorpora().getValue(BenchmarkCorpusSize.Medium)
        val second = VaultSearchBenchmarkFixtures.buildCorpora().getValue(BenchmarkCorpusSize.Medium)

        assertEquals(
            VaultSearchBenchmarkFixtures.digest(first),
            VaultSearchBenchmarkFixtures.digest(second),
        )
        assertEquals(first.metadata, second.metadata)
        assertEquals(first.candidates.map { it.id }, second.candidates.map { it.id })
    }

    @Test
    fun `initial build benchmark`() = runTest {
        listOf(
            BenchmarkCorpusSize.Small,
            BenchmarkCorpusSize.Medium,
            BenchmarkCorpusSize.Large,
        ).forEach { size ->
            val corpus = corpora.getValue(size)
            val result =
                harness.run(
                    caseName = "initial-build/${size.label}",
                    corpus = corpus,
                ) {
                    createBuilder().build(
                        items = corpus.items,
                        metadata = corpus.metadata,
                        surface = "vault-search-benchmark",
                    )
                }
            assertEquals(corpus.itemCount, result.itemCount)
            assertTrue(result.samplesNs.isNotEmpty())
        }
    }

    @Test
    fun `incremental rebuild benchmark`() = runTest {
        val corpus = corpora.getValue(BenchmarkCorpusSize.Large)
        val changed = VaultSearchBenchmarkFixtures.withSingleChangedItem(corpus)
        val result =
            harness.run(
                caseName = "incremental-rebuild",
                corpus = corpus,
                prepare = {
                    createBuilder().also { builder ->
                        builder.build(
                            items = corpus.items,
                            metadata = corpus.metadata,
                            surface = "vault-search-benchmark",
                        )
                    }
                },
            ) { builder ->
                builder.build(
                    items = changed.items,
                    metadata = changed.metadata,
                    surface = "vault-search-benchmark",
                )
            }
        assertEquals(corpus.itemCount, result.itemCount)
        assertTrue(result.samplesNs.isNotEmpty())
    }

    @Test
    fun `metadata only rebuild benchmark`() = runTest {
        val corpus = corpora.getValue(BenchmarkCorpusSize.Large)
        val changed = VaultSearchBenchmarkFixtures.withChangedMetadata(corpus)
        val result =
            harness.run(
                caseName = "metadata-only-rebuild",
                corpus = corpus,
                prepare = {
                    createBuilder().also { builder ->
                        builder.build(
                            items = corpus.items,
                            metadata = corpus.metadata,
                            surface = "vault-search-benchmark",
                        )
                    }
                },
            ) { builder ->
                builder.build(
                    items = changed.items,
                    metadata = changed.metadata,
                    surface = "vault-search-benchmark",
                )
            }
        assertEquals(corpus.itemCount, result.itemCount)
        assertTrue(result.samplesNs.isNotEmpty())
    }

    @Test
    fun `compile only benchmark`() = runTest {
        val indices = prepareIndices()
        queries.forEach { query ->
            val corpus = corpora.getValue(query.corpusSize)
            val index = indices.getValue(query.corpusSize)
            val result =
                harness.run(
                    caseName = "compile/${query.name}",
                    corpus = corpus,
                ) {
                    assertNotNull(
                        index.compile(
                            query = query.query,
                            searchBy = query.searchBy,
                        ),
                    )
                }
            assertTrue(result.samplesNs.isNotEmpty())
        }
    }

    @Test
    fun `evaluate only benchmark`() = runTest {
        val preparedQueries = prepareCompiledQueries(prepareIndices())
        preparedQueries.forEach { entry ->
            val result =
                harness.run(
                    caseName = "evaluate/${entry.query.name}",
                    corpus = entry.corpus,
                ) {
                    entry.index.evaluate(
                        plan = entry.plan,
                        candidates = entry.corpus.candidates,
                        highlightBackgroundColor = Color.Unspecified,
                        highlightContentColor = Color.Unspecified,
                    )
                }
            assertTrue(result.samplesNs.isNotEmpty())
            assertTrue(result.itemCount == entry.corpus.itemCount)
        }
    }

    @Test
    fun `compile and evaluate benchmark`() = runTest {
        val indices = prepareIndices()
        queries.forEach { query ->
            val corpus = corpora.getValue(query.corpusSize)
            val index = indices.getValue(query.corpusSize)
            val result =
                harness.run(
                    caseName = "compile-evaluate/${query.name}",
                    corpus = corpus,
                ) {
                    val plan =
                        assertNotNull(
                            index.compile(
                                query = query.query,
                                searchBy = query.searchBy,
                            ),
                        )
                    index.evaluate(
                        plan = plan,
                        candidates = corpus.candidates,
                        highlightBackgroundColor = Color.Unspecified,
                        highlightContentColor = Color.Unspecified,
                    )
                }
            assertTrue(result.samplesNs.isNotEmpty())
        }
    }

    @Test
    fun `parallel threshold query returns many results`() = runTest {
        val largeCorpus = corpora.getValue(BenchmarkCorpusSize.Large)
        val largeIndex = buildIndex(BenchmarkCorpusSize.Large)
        val query = queries.first { it.name == "parallel-threshold" }
        val plan =
            assertNotNull(
                largeIndex.compile(
                    query = query.query,
                    searchBy = query.searchBy,
                ),
            )
        val evaluated =
            largeIndex.evaluate(
                plan = plan,
                candidates = largeCorpus.candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            )
        assertTrue(evaluated.size > 4096)
    }

    private suspend fun prepareIndices(): Map<BenchmarkCorpusSize, VaultSearchIndex> = mapOf(
        BenchmarkCorpusSize.Medium to buildIndex(BenchmarkCorpusSize.Medium),
        BenchmarkCorpusSize.Large to buildIndex(BenchmarkCorpusSize.Large),
    )

    private suspend fun buildIndex(size: BenchmarkCorpusSize): VaultSearchIndex =
        createBuilder().build(
            items = corpora.getValue(size).items,
            metadata = corpora.getValue(size).metadata,
            surface = "vault-search-benchmark",
        )

    private suspend fun prepareCompiledQueries(
        indices: Map<BenchmarkCorpusSize, VaultSearchIndex>,
    ): List<PreparedQuery> = queries.map { query ->
        val corpus = corpora.getValue(query.corpusSize)
        val index = indices.getValue(query.corpusSize)
        val plan =
            assertNotNull(
                index.compile(
                    query = query.query,
                    searchBy = query.searchBy,
                ),
            )
        PreparedQuery(
            query = query,
            corpus = corpus,
            index = index,
            plan = plan,
        )
    }

    private fun createBuilder(): DefaultVaultSearchIndexBuilder = DefaultVaultSearchIndexBuilder(
        tokenizer = tokenizer,
        scorer = scorer,
        executor = executor,
        parser = parser,
        compiler = compiler,
    )

    private data class PreparedQuery(
        val query: BenchmarkQueryCase,
        val corpus: BenchmarkCorpus,
        val index: VaultSearchIndex,
        val plan: CompiledQueryPlan,
    )
}
