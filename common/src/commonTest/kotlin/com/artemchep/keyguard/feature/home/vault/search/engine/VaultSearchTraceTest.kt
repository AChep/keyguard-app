package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.createItem
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VaultSearchTraceTest {
    @Test
    fun `index build emits start and finish trace events`() = runTest {
        val traceSink = RecordingVaultSearchTraceSink()
        val builder = createBuilder(traceSink)

        builder.build(
            items = listOf(
                createSecret(
                    id = "alpha",
                    name = "Alpha",
                    login = DSecret.Login(username = "alice@example.com"),
                ),
            ),
            surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        )

        assertEquals(
            listOf(IndexTracePhase.Start, IndexTracePhase.Finish),
            traceSink.indexEvents.map(IndexTraceEvent::phase),
        )
        assertEquals(VAULT_SEARCH_SURFACE_VAULT_LIST, traceSink.indexEvents.last().surface)
        assertTrue(traceSink.indexEvents.last().hotPostingCounts.isNotEmpty())
    }

    @Test
    fun `evaluation trace records kept and dropped items`() = runTest {
        val traceSink = RecordingVaultSearchTraceSink()
        val builder = createBuilder(traceSink)
        val matching = createItem(
            createSecret(
                id = "alpha",
                name = "Email Login",
                login = DSecret.Login(username = "alice@example.com"),
            ),
        )
        val missed = createItem(
            createSecret(
                id = "beta",
                name = "Other Login",
                login = DSecret.Login(username = "bob@example.com"),
            ),
        )
        val index = builder.build(
            items = listOf(matching.source, missed.source),
            surface = VAULT_SEARCH_SURFACE_QUICK_SEARCH,
        )

        val plan = index.compile("username:alice", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(plan)
        val out = index.evaluate(
            plan = plan,
            candidates = listOf(matching, missed),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        )

        assertEquals(listOf("alpha"), out.map { it.id })
        assertEquals(1, traceSink.queryEvents.size)
        assertEquals(1, traceSink.evaluationEvents.size)
        assertEquals(2, traceSink.itemEvents.size)
        assertEquals(1, traceSink.evaluationEvents.single().finalResultCount)
        assertEquals(
            setOf(ItemTraceDisposition.Kept, ItemTraceDisposition.DroppedByTextMiss),
            traceSink.itemEvents.map(ItemTraceEvent::disposition).toSet(),
        )
        val matchingTrace = traceSink.itemEvents.first { it.itemId == "alpha" }
        assertTrue(matchingTrace.clauses.any { it.matched && it.matchedField == "username" })
    }

    @Test
    fun `password trace keeps raw query but masks stored secret values`() = runTest {
        val traceSink = RecordingVaultSearchTraceSink()
        val builder = createBuilder(traceSink)
        val secret = createSecret(
            id = "alpha",
            name = "Bank",
            notes = "stored note payload",
            login = DSecret.Login(
                username = "alice",
                password = "hidden-password-value",
            ),
        )
        val item = createItem(secret)
        val index = builder.build(
            items = listOf(secret),
            surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        )

        val plan = index.compile("password:hidden", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(plan)
        index.evaluate(
            plan = plan,
            candidates = listOf(item),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        )

        val rendered = buildList {
            addAll(traceSink.queryEvents.map(QueryTraceEvent::render))
            addAll(traceSink.evaluationEvents.map(EvaluationTraceEvent::render))
            addAll(traceSink.itemEvents.map(ItemTraceEvent::render))
        }.joinToString(separator = "\n")

        assertTrue(rendered.contains("password:hidden"))
        assertFalse(rendered.contains("hidden-password-value"))
        assertFalse(rendered.contains("stored note payload"))
        assertTrue(
            traceSink.itemEvents.single().clauses.any { clause ->
                clause.stage == "cold-scan" && clause.matchedField == "password"
            },
        )
    }

    private fun createBuilder(
        traceSink: VaultSearchTraceSink,
    ): DefaultVaultSearchIndexBuilder {
        val tokenizer = DefaultSearchTokenizer()
        return DefaultVaultSearchIndexBuilder(
            tokenizer = tokenizer,
            scorer = Bm25SearchScorer(),
            executor = DefaultSearchExecutor(),
            parser = DefaultVaultSearchParser(),
            compiler = DefaultVaultSearchQueryCompiler(tokenizer),
            traceSink = traceSink,
        )
    }
}

private class RecordingVaultSearchTraceSink : VaultSearchTraceSink {
    override val isEnabled: Boolean = true

    val indexEvents = mutableListOf<IndexTraceEvent>()
    val queryEvents = mutableListOf<QueryTraceEvent>()
    val evaluationEvents = mutableListOf<EvaluationTraceEvent>()
    val itemEvents = mutableListOf<ItemTraceEvent>()
    val surfaceEvents = mutableListOf<SurfaceTraceEvent>()

    override fun index(event: IndexTraceEvent) {
        indexEvents += event
    }

    override fun query(event: QueryTraceEvent) {
        queryEvents += event
    }

    override fun evaluation(event: EvaluationTraceEvent) {
        evaluationEvents += event
    }

    override fun item(event: ItemTraceEvent) {
        itemEvents += event
    }

    override fun surface(event: SurfaceTraceEvent) {
        surfaceEvents += event
    }
}
