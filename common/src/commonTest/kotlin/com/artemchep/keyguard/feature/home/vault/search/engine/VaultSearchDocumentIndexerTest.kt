package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultSearchDocumentIndexerTest {
    private val indexer = VaultSearchDocumentIndexer(DefaultSearchTokenizer())

    @Test
    fun `shares exact frequencies when folded terms are identical`() {
        val document = indexer.build(
            docId = 0,
            secret = createSecret(id = "ascii", name = "Bank Portal"),
        ).document
        val title = document.hotFields.getValue(VaultTextField.Title)

        assertTrue(title.termFrequencies === title.exactTermFrequencies)
        assertEquals(mapOf("bank" to 1, "portal" to 1), title.termFrequencies)
    }

    @Test
    fun `keeps complete exact frequencies when a later value folds differently`() {
        val document = indexer.build(
            docId = 0,
            secret = createSecret(
                id = "mixed",
                fields = listOf(
                    DSecret.Field(name = "plain", type = DSecret.Field.Type.Text),
                    DSecret.Field(name = "José", type = DSecret.Field.Type.Text),
                ),
            ),
        ).document
        val fieldNames = document.hotFields.getValue(VaultTextField.FieldName)

        assertFalse(fieldNames.termFrequencies === fieldNames.exactTermFrequencies)
        assertEquals(mapOf("plain" to 1, "jose" to 1), fieldNames.termFrequencies)
        assertEquals(mapOf("plain" to 1, "josé" to 1), fieldNames.exactTermFrequencies)
    }
}
