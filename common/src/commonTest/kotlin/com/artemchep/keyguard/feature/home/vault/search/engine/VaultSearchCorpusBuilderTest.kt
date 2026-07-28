package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultSearchCorpusBuilderTest {
    private val tokenizer = DefaultSearchTokenizer()
    private val documentIndexer = VaultSearchDocumentIndexer(tokenizer)

    @Test
    fun `build corpus aggregates hot postings field stats and exact facets`() {
        val alpha =
            createSecret(
                id = "alpha",
                name = "Shared Shared",
                notes = "Cold Value",
            )
        val beta =
            createSecret(
                id = "beta",
                name = "Shared Beta",
            )
        val corpus =
            buildSearchCorpus(
                buildResults =
                    listOf(
                        documentIndexer.build(docId = 0, secret = alpha),
                        documentIndexer.build(docId = 1, secret = beta),
                    ),
                tokenizer = tokenizer,
            )

        assertEquals(mapOf(alpha.id to 0, beta.id to 1), corpus.docIdsBySourceId)
        assertEquals(
            listOf(
                SearchPosting(docId = 0, termFrequency = 2),
                SearchPosting(docId = 1, termFrequency = 1),
            ),
            corpus.postings.getValue(VaultTextField.Title).getValue("shared"),
        )
        assertNull(corpus.postings[VaultTextField.Note])
        assertEquals(2.0, corpus.fieldStats.getValue(VaultTextField.Title).averageLength)
        assertEquals(
            mapOf("shared" to 2, "beta" to 1),
            corpus.fieldStats.getValue(VaultTextField.Title).documentFrequency,
        )
        assertEquals(2.0, corpus.fieldStats.getValue(VaultTextField.Note).averageLength)
        assertEquals(
            mapOf("cold" to 1, "value" to 1),
            corpus.fieldStats.getValue(VaultTextField.Note).documentFrequency,
        )
        assertEquals(setOf(0, 1), corpus.exactFacets.account["account-id"])
    }
}
