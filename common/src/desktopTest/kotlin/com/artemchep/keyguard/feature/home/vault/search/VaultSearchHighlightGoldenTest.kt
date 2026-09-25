package com.artemchep.keyguard.feature.home.vault.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.benchmark.BenchmarkCorpusSize
import com.artemchep.keyguard.feature.home.vault.search.benchmark.VaultSearchBenchmarkFixtures
import com.artemchep.keyguard.feature.home.vault.search.engine.Bm25SearchScorer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchExecutor
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultVaultSearchIndexBuilder
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndex
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultSearchHighlightGoldenTest {
    private val tokenizer = DefaultSearchTokenizer()
    private val parser = DefaultVaultSearchParser()
    private val compiler = DefaultVaultSearchQueryCompiler(tokenizer)
    private val scorer = Bm25SearchScorer()
    private val executor = DefaultSearchExecutor()
    private val corpus = VaultSearchBenchmarkFixtures.buildCorpora()
        .getValue(BenchmarkCorpusSize.Small)

    private val highlightBackgroundColor = Color(0xFF123456)
    private val highlightContentColor = Color(0xFF654321)

    private val goldenQueries = mapOf(
        "alice" to """
            count=167;sha256=2777defa997370ad8a0e7fd6c032f2362757dc6b45fa261735ae3733637ea087
            id=secret-0 title=Bank Portal 0 spans=[] badge=Email:alice0@bank.example.com
            id=secret-2 title=Shared Portal 2 spans=[] badge=Email:alice2@bank.example.com
            id=secret-4 title=Bank Portal 4 spans=[] badge=Email:alice4@bank.example.com
            id=secret-6 title=Shared Portal 6 spans=[] badge=Email:alice6@bank.example.com
            id=secret-8 title=Bank Portal 8 spans=[] badge=Email:alice8@bank.example.com
            id=secret-10 title=Shared Portal 10 spans=[] badge=Email:alice10@bank.example.com
            id=secret-12 title=Bank Portal 12 spans=[] badge=Email:alice12@bank.example.com
            id=secret-14 title=Shared Portal 14 spans=[] badge=Email:alice14@bank.example.com
            id=secret-16 title=Bank Portal 16 spans=[] badge=Email:alice16@bank.example.com
            id=secret-18 title=Shared Portal 18 spans=[] badge=Email:alice18@bank.example.com
        """.trimIndent(),
        "ALICE" to """
            count=167;sha256=2777defa997370ad8a0e7fd6c032f2362757dc6b45fa261735ae3733637ea087
            id=secret-0 title=Bank Portal 0 spans=[] badge=Email:alice0@bank.example.com
            id=secret-2 title=Shared Portal 2 spans=[] badge=Email:alice2@bank.example.com
            id=secret-4 title=Bank Portal 4 spans=[] badge=Email:alice4@bank.example.com
            id=secret-6 title=Shared Portal 6 spans=[] badge=Email:alice6@bank.example.com
            id=secret-8 title=Bank Portal 8 spans=[] badge=Email:alice8@bank.example.com
            id=secret-10 title=Shared Portal 10 spans=[] badge=Email:alice10@bank.example.com
            id=secret-12 title=Bank Portal 12 spans=[] badge=Email:alice12@bank.example.com
            id=secret-14 title=Shared Portal 14 spans=[] badge=Email:alice14@bank.example.com
            id=secret-16 title=Bank Portal 16 spans=[] badge=Email:alice16@bank.example.com
            id=secret-18 title=Shared Portal 18 spans=[] badge=Email:alice18@bank.example.com
        """.trimIndent(),
        "bank portal" to """
            count=250;sha256=3f65a55ddb81358a03ac731015f180ab10f795c489626cf2a6c06f2cd458410d
            id=secret-0 title=Bank Portal 0 spans=[0..4,5..11] badge=PasskeyDisplayName:Portal user 0
            id=secret-44 title=Bank Portal 44 spans=[0..4,5..11] badge=PasskeyDisplayName:Portal user 44
            id=secret-88 title=Bank Portal 88 spans=[0..4,5..11] badge=PasskeyDisplayName:Portal user 88
            id=secret-132 title=Bank Portal 132 spans=[0..4,5..11] badge=PasskeyDisplayName:Portal user 132
            id=secret-176 title=Bank Portal 176 spans=[0..4,5..11] badge=PasskeyDisplayName:Portal user 176
            id=secret-220 title=Bank Portal 220 spans=[0..4,5..11] badge=PasskeyDisplayName:Portal user 220
            id=secret-4 title=Bank Portal 4 spans=[0..4,5..11] badge=null
            id=secret-8 title=Bank Portal 8 spans=[0..4,5..11] badge=null
            id=secret-12 title=Bank Portal 12 spans=[0..4,5..11] badge=null
            id=secret-16 title=Bank Portal 16 spans=[0..4,5..11] badge=null
        """.trimIndent(),
        "port" to """
            count=250;sha256=1dbb6c4cebc92074f390a71c8dc570daef6f9dc64d90401421cbc772578e99b7
            id=secret-0 title=Bank Portal 0 spans=[5..9] badge=PasskeyDisplayName:Portal user 0
            id=secret-11 title=Ops Portal 11 spans=[4..8] badge=PasskeyDisplayName:Portal user 11
            id=secret-22 title=Shared Portal 22 spans=[7..11] badge=PasskeyDisplayName:Portal user 22
            id=secret-33 title=Project Portal 33 spans=[8..12] badge=PasskeyDisplayName:Portal user 33
            id=secret-44 title=Bank Portal 44 spans=[5..9] badge=PasskeyDisplayName:Portal user 44
            id=secret-55 title=Ops Portal 55 spans=[4..8] badge=PasskeyDisplayName:Portal user 55
            id=secret-66 title=Shared Portal 66 spans=[7..11] badge=PasskeyDisplayName:Portal user 66
            id=secret-77 title=Project Portal 77 spans=[8..12] badge=PasskeyDisplayName:Portal user 77
            id=secret-88 title=Bank Portal 88 spans=[5..9] badge=PasskeyDisplayName:Portal user 88
            id=secret-99 title=Ops Portal 99 spans=[4..8] badge=PasskeyDisplayName:Portal user 99
        """.trimIndent(),
        "portal" to """
            count=250;sha256=5cf5e7937523cb84122a9d6c57f5c7a1e91a5b665ca0c41289d5d5862863480c
            id=secret-0 title=Bank Portal 0 spans=[5..11] badge=PasskeyDisplayName:Portal user 0
            id=secret-11 title=Ops Portal 11 spans=[4..10] badge=PasskeyDisplayName:Portal user 11
            id=secret-22 title=Shared Portal 22 spans=[7..13] badge=PasskeyDisplayName:Portal user 22
            id=secret-33 title=Project Portal 33 spans=[8..14] badge=PasskeyDisplayName:Portal user 33
            id=secret-44 title=Bank Portal 44 spans=[5..11] badge=PasskeyDisplayName:Portal user 44
            id=secret-55 title=Ops Portal 55 spans=[4..10] badge=PasskeyDisplayName:Portal user 55
            id=secret-66 title=Shared Portal 66 spans=[7..13] badge=PasskeyDisplayName:Portal user 66
            id=secret-77 title=Project Portal 77 spans=[8..14] badge=PasskeyDisplayName:Portal user 77
            id=secret-88 title=Bank Portal 88 spans=[5..11] badge=PasskeyDisplayName:Portal user 88
            id=secret-99 title=Ops Portal 99 spans=[4..10] badge=PasskeyDisplayName:Portal user 99
        """.trimIndent(),
        "\"Bank Portal\"" to """
            count=63;sha256=7fc63f6fe333b7af819e2ca45fca69e6fd8294593860116497d0193bf577bb19
            id=secret-0 title=Bank Portal 0 spans=[0..4,5..11] badge=null
            id=secret-4 title=Bank Portal 4 spans=[0..4,5..11] badge=null
            id=secret-8 title=Bank Portal 8 spans=[0..4,5..11] badge=null
            id=secret-12 title=Bank Portal 12 spans=[0..4,5..11] badge=null
            id=secret-16 title=Bank Portal 16 spans=[0..4,5..11] badge=null
            id=secret-20 title=Bank Portal 20 spans=[0..4,5..11] badge=null
            id=secret-24 title=Bank Portal 24 spans=[0..4,5..11] badge=null
            id=secret-28 title=Bank Portal 28 spans=[0..4,5..11] badge=null
            id=secret-32 title=Bank Portal 32 spans=[0..4,5..11] badge=null
            id=secret-36 title=Bank Portal 36 spans=[0..4,5..11] badge=null
        """.trimIndent(),
        "pörtal" to """
            count=250;sha256=6582400a3943f3cbf172b4a14546af515c0b57faefd2beb393c4bf373234b1e7
            id=secret-0 title=Bank Portal 0 spans=[5..11] badge=PasskeyDisplayName:Portal user 0
            id=secret-11 title=Ops Portal 11 spans=[4..10] badge=PasskeyDisplayName:Portal user 11
            id=secret-22 title=Shared Portal 22 spans=[7..13] badge=PasskeyDisplayName:Portal user 22
            id=secret-33 title=Project Portal 33 spans=[8..14] badge=PasskeyDisplayName:Portal user 33
            id=secret-44 title=Bank Portal 44 spans=[5..11] badge=PasskeyDisplayName:Portal user 44
            id=secret-55 title=Ops Portal 55 spans=[4..10] badge=PasskeyDisplayName:Portal user 55
            id=secret-66 title=Shared Portal 66 spans=[7..13] badge=PasskeyDisplayName:Portal user 66
            id=secret-77 title=Project Portal 77 spans=[8..14] badge=PasskeyDisplayName:Portal user 77
            id=secret-88 title=Bank Portal 88 spans=[5..11] badge=PasskeyDisplayName:Portal user 88
            id=secret-99 title=Ops Portal 99 spans=[4..10] badge=PasskeyDisplayName:Portal user 99
        """.trimIndent(),
        "folder:work" to """
            count=84;sha256=64748e4712a1deae41ea2090b1d35b79a611be5d75df852a0e61838856208590
            id=secret-0 title=Bank Portal 0 spans=[] badge=null
            id=secret-3 title=Ops Portal 3 spans=[] badge=null
            id=secret-6 title=Shared Portal 6 spans=[] badge=null
            id=secret-9 title=Project Portal 9 spans=[] badge=null
            id=secret-12 title=Bank Portal 12 spans=[] badge=null
            id=secret-15 title=Ops Portal 15 spans=[] badge=null
            id=secret-18 title=Shared Portal 18 spans=[] badge=null
            id=secret-21 title=Project Portal 21 spans=[] badge=null
            id=secret-24 title=Bank Portal 24 spans=[] badge=null
            id=secret-27 title=Ops Portal 27 spans=[] badge=null
        """.trimIndent(),
        "tag:ops" to """
            count=125;sha256=6c836136c31ce98a08b7d1b4bb970fc8313ecd73fce32925649cf568bf580658
            id=secret-0 title=Bank Portal 0 spans=[] badge=null
            id=secret-2 title=Shared Portal 2 spans=[] badge=null
            id=secret-4 title=Bank Portal 4 spans=[] badge=null
            id=secret-6 title=Shared Portal 6 spans=[] badge=null
            id=secret-8 title=Bank Portal 8 spans=[] badge=null
            id=secret-10 title=Shared Portal 10 spans=[] badge=null
            id=secret-12 title=Bank Portal 12 spans=[] badge=null
            id=secret-14 title=Shared Portal 14 spans=[] badge=null
            id=secret-16 title=Bank Portal 16 spans=[] badge=null
            id=secret-18 title=Shared Portal 18 spans=[] badge=null
        """.trimIndent(),
        "favorite:true" to """
            count=0;sha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        """.trimIndent(),
        "username:alice tag:ops" to """
            count=125;sha256=f858785afc8203228f4802bcfed8a3ce42b4266331547c41e9b5a24e8a9ff994
            id=secret-0 title=Bank Portal 0 spans=[] badge=Username:alice0@bank.example.com
            id=secret-2 title=Shared Portal 2 spans=[] badge=Username:alice2@bank.example.com
            id=secret-4 title=Bank Portal 4 spans=[] badge=Username:alice4@bank.example.com
            id=secret-6 title=Shared Portal 6 spans=[] badge=Username:alice6@bank.example.com
            id=secret-8 title=Bank Portal 8 spans=[] badge=Username:alice8@bank.example.com
            id=secret-10 title=Shared Portal 10 spans=[] badge=Username:alice10@bank.example.com
            id=secret-12 title=Bank Portal 12 spans=[] badge=Username:alice12@bank.example.com
            id=secret-14 title=Shared Portal 14 spans=[] badge=Username:alice14@bank.example.com
            id=secret-16 title=Bank Portal 16 spans=[] badge=Username:alice16@bank.example.com
            id=secret-18 title=Shared Portal 18 spans=[] badge=Username:alice18@bank.example.com
        """.trimIndent(),
        "note:\"temp pin\"" to """
            count=77;sha256=6f2c251b5cb7e1a7efd74ba18bcfd9d628a17c83dae294fdd78fd59a08dd2fb0
            id=secret-3 title=Ops Portal 3 spans=[] badge=Note:temp pin 3 keep quiet
            id=secret-6 title=Shared Portal 6 spans=[] badge=Note:temp pin 6 keep quiet
            id=secret-9 title=Project Portal 9 spans=[] badge=Note:temp pin 9 keep quiet
            id=secret-12 title=Bank Portal 12 spans=[] badge=Note:temp pin 12 keep quiet
            id=secret-15 title=Ops Portal 15 spans=[] badge=Note:temp pin 15 keep quiet
            id=secret-18 title=Shared Portal 18 spans=[] badge=Note:temp pin 18 keep quiet
            id=secret-21 title=Project Portal 21 spans=[] badge=Note:temp pin 21 keep quiet
            id=secret-24 title=Bank Portal 24 spans=[] badge=Note:temp pin 24 keep quiet
            id=secret-27 title=Ops Portal 27 spans=[] badge=Note:temp pin 27 keep quiet
            id=secret-30 title=Shared Portal 30 spans=[] badge=Note:temp pin 30 keep quiet
        """.trimIndent(),
        "project -tag:archive" to """
            count=42;sha256=a9958d885340744adb0e8891068187aa169b2f571e17d6d5eb3406c703943776
            id=secret-1 title=Project Portal 1 spans=[0..7] badge=null
            id=secret-5 title=Project Portal 5 spans=[0..7] badge=null
            id=secret-13 title=Project Portal 13 spans=[0..7] badge=null
            id=secret-17 title=Project Portal 17 spans=[0..7] badge=null
            id=secret-25 title=Project Portal 25 spans=[0..7] badge=null
            id=secret-29 title=Project Portal 29 spans=[0..7] badge=null
            id=secret-37 title=Project Portal 37 spans=[0..7] badge=null
            id=secret-41 title=Project Portal 41 spans=[0..7] badge=null
            id=secret-49 title=Project Portal 49 spans=[0..7] badge=null
            id=secret-53 title=Project Portal 53 spans=[0..7] badge=null
        """.trimIndent(),
        "zzz-does-not-exist" to """
            count=0;sha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        """.trimIndent(),
    )

    @Test
    fun `evaluate matches golden snapshots`() = runTest {
        val index = buildIndex()
        goldenQueries.forEach { (query, expected) ->
            val plan = assertNotNull(
                index.compile(
                    query = query,
                    searchBy = VaultRoute.Args.SearchBy.ALL,
                ),
                "query: $query",
            )
            val results = index.evaluate(
                plan = plan,
                candidates = corpus.candidates,
                highlightBackgroundColor = highlightBackgroundColor,
                highlightContentColor = highlightContentColor,
            )
            results.forEach { item ->
                item.title.spanStyles.forEach { span ->
                    assertEquals(FontWeight.Bold, span.item.fontWeight, "query: $query")
                    assertEquals(highlightBackgroundColor, span.item.background, "query: $query")
                    assertEquals(highlightContentColor, span.item.color, "query: $query")
                }
            }
            assertEquals(expected, serialize(results), "query: $query")
        }
    }

    @Test
    fun `blank query compiles to no plan and evaluate passes candidates through`() = runTest {
        val index = buildIndex()
        assertNull(index.compile(query = "   "))
        val results = index.evaluate(
            plan = null,
            candidates = corpus.candidates,
            highlightBackgroundColor = highlightBackgroundColor,
            highlightContentColor = highlightContentColor,
        )
        assertEquals(corpus.candidates, results)
        assertTrue(results.all { it.searchContextBadge == null })
        assertTrue(results.all { it.title.spanStyles.isEmpty() })
    }

    private suspend fun buildIndex(): VaultSearchIndex = DefaultVaultSearchIndexBuilder(
        tokenizer = tokenizer,
        scorer = scorer,
        executor = executor,
        parser = parser,
        compiler = compiler,
    ).build(
        items = corpus.items,
        metadata = corpus.metadata,
        surface = "vault-search-golden",
    )

    private fun serialize(items: List<VaultItem2.Item>): String {
        val lines = items.map { item ->
            val spans = item.title.spanStyles
                .joinToString(separator = ",") { span -> "${span.start}..${span.end}" }
            val badge = item.searchContextBadge
                ?.let { badge -> "${badge.field.name}:${badge.text}" }
            "id=${item.id} title=${item.title.text} spans=[$spans] badge=$badge"
        }
        val digest = sha256(lines.joinToString(separator = "\n"))
        return buildString {
            append("count=${lines.size};sha256=$digest")
            lines.take(GOLDEN_HEAD_SIZE).forEach { line ->
                append('\n')
                append(line)
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        private const val GOLDEN_HEAD_SIZE = 10
    }
}
