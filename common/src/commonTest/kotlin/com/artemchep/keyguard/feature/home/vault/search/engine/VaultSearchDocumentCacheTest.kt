package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultSearchDocumentCacheTest {
    private val documentIndexer = VaultSearchDocumentIndexer(DefaultSearchTokenizer())

    @Test
    fun `reconcile preserves ids while adding and removing documents`() {
        val alpha = createSecret(id = "alpha", name = "Alpha")
        val beta = createSecret(id = "beta", name = "Beta")
        val gamma = createSecret(id = "gamma", name = "Gamma")
        val initial =
            reconcile(
                items = listOf(alpha, beta),
                dataRevCounters = mapOf(alpha.id to 0L, beta.id to 0L),
            )

        assertEquals(0, initial.reusedDocumentCount)
        assertEquals(2, initial.rebuiltDocumentCount)
        assertEquals(0, initial.removedDocumentCount)
        assertTrue(initial.hasCorpusChanges)
        val betaDocId =
            initial.documentsBySourceId
                .getValue(beta.id)
                .buildResult.document.docId

        val updated =
            reconcile(
                previous = initial.asCachedCorpusState(),
                items = listOf(beta, gamma),
                dataRevCounters = mapOf(beta.id to 0L, gamma.id to 0L),
            )

        assertEquals(1, updated.reusedDocumentCount)
        assertEquals(1, updated.rebuiltDocumentCount)
        assertEquals(1, updated.removedDocumentCount)
        assertEquals(
            betaDocId,
            updated.documentsBySourceId
                .getValue(beta.id)
                .buildResult.document.docId,
        )
        assertEquals(
            initial.nextDocId,
            updated.documentsBySourceId
                .getValue(gamma.id)
                .buildResult.document.docId,
        )
        assertEquals(initial.nextDocId + 1, updated.nextDocId)
    }

    @Test
    fun `reconcile treats reordering as an unchanged corpus`() {
        val alpha = createSecret(id = "alpha", name = "Alpha")
        val beta = createSecret(id = "beta", name = "Beta")
        val revisions = mapOf(alpha.id to 0L, beta.id to 0L)
        val initial =
            reconcile(
                items = listOf(alpha, beta),
                dataRevCounters = revisions,
            )

        val reordered =
            reconcile(
                previous = initial.asCachedCorpusState(),
                items = listOf(beta, alpha),
                dataRevCounters = revisions,
            )

        assertEquals(2, reordered.reusedDocumentCount)
        assertEquals(0, reordered.rebuiltDocumentCount)
        assertEquals(0, reordered.removedDocumentCount)
        assertFalse(reordered.hasCorpusChanges)
        assertEquals(listOf(beta.id, alpha.id), reordered.documentsBySourceId.keys.toList())
        assertEquals(
            initial.documentsBySourceId
                .getValue(alpha.id)
                .buildResult.document.docId,
            reordered.documentsBySourceId
                .getValue(alpha.id)
                .buildResult.document.docId,
        )
        assertEquals(
            initial.documentsBySourceId
                .getValue(beta.id)
                .buildResult.document.docId,
            reordered.documentsBySourceId
                .getValue(beta.id)
                .buildResult.document.docId,
        )
    }

    private fun reconcile(
        previous: CachedCorpusState = CachedCorpusState(),
        items: List<DSecret>,
        dataRevCounters: Map<String, Long>,
    ): DocumentCacheUpdate =
        reconcileDocuments(
            previous = previous,
            items = items,
            dataRevCounters = dataRevCounters,
            fingerprintOf = ::searchFingerprint,
            buildDocument = documentIndexer::build,
        )

    private fun DocumentCacheUpdate.asCachedCorpusState(): CachedCorpusState =
        CachedCorpusState(
            nextDocId = nextDocId,
            documentsBySourceId = documentsBySourceId,
        )
}
