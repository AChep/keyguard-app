package com.artemchep.keyguard.feature.home.vault.search.query

import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import com.artemchep.keyguard.feature.home.vault.search.query.model.NegatedNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.QualifiedTermNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.TextTermNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultSearchParserTest {
    private val parser = DefaultVaultSearchParser()

    @Test
    fun `parses mixed bare qualified and negated clauses`() {
        val out = parser.parse("username:alice project -note:\"temp pin\"")

        assertEquals(3, out.clauses.size)
        assertIs<QualifiedTermNode>(out.clauses[0])
        assertIs<TextTermNode>(out.clauses[1])
        val negated = assertIs<NegatedNode>(out.clauses[2])
        assertIs<QualifiedTermNode>(negated.clause)
    }

    @Test
    fun `keeps unknown qualifier as qualified node`() {
        val out = parser.parse("unknown:value")
        val clause = assertIs<QualifiedTermNode>(out.clauses.single())
        assertEquals("unknown", clause.qualifier)
        assertEquals("value", clause.value?.value)
    }

    @Test
    fun `reports missing qualifier value`() {
        val out = parser.parse("username:")
        assertEquals(1, out.clauses.size)
        assertTrue(out.diagnostics.isNotEmpty())
        assertTrue(out.diagnostics.any { it.message.contains("Missing value") })
    }
}
