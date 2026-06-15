package com.artemchep.keyguard.feature.home.vault.search.query

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.DefaultVaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightRole
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightSpan
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultSearchQueryHighlighterTest {
    private val highlighter = DefaultVaultSearchQueryHighlighter(
        parser = DefaultVaultSearchParser(),
    )
    private val localizedQualifierCatalog =
        buildVaultSearchQualifierCatalog(
            localizedAliasesByCanonicalName =
                mapOf(
                    "folder" to setOf("dossier"),
                    "organization" to setOf("organisation"),
                    "account" to setOf("compte"),
                    "tag" to setOf("etiquette"),
                    "collection" to setOf("collection"),
                ),
        )

    @Test
    fun `does not highlight bare text clause`() {
        assertEquals(
            emptyList(),
            highlighter.highlight("alpha", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights qualified text clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 14,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("username:alice", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights card brand qualified text clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 15,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("card-brand:visa", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights attachment qualifier as text clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = "attachment:file".length,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("attachment:file", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights passkey qualifier as text clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = "passkey:device".length,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("passkey:device", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights ssh qualifier as text clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = "ssh:fingerprint".length,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("ssh:fingerprint", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights host alias as qualified text clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 16,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("host:example.com", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights facet clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 11,
                    role = QueryHighlightRole.FacetClause,
                ),
            ),
            highlighter.highlight("folder:work", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights removed qualifiers as text clauses`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = "type:login".length,
                    role = QueryHighlightRole.TextClause,
                ),
                QueryHighlightSpan(
                    start = "type:login ".length,
                    end = "type:login favorite:true".length,
                    role = QueryHighlightRole.TextClause,
                ),
                QueryHighlightSpan(
                    start = "type:login favorite:true ".length,
                    end = "type:login favorite:true reprompt:true".length,
                    role = QueryHighlightRole.TextClause,
                ),
                QueryHighlightSpan(
                    start = "type:login favorite:true reprompt:true ".length,
                    end = "type:login favorite:true reprompt:true otp:true".length,
                    role = QueryHighlightRole.TextClause,
                ),
                QueryHighlightSpan(
                    start = "type:login favorite:true reprompt:true otp:true ".length,
                    end = "type:login favorite:true reprompt:true otp:true attachments:true".length,
                    role = QueryHighlightRole.TextClause,
                ),
                QueryHighlightSpan(
                    start = "type:login favorite:true reprompt:true otp:true attachments:true ".length,
                    end = "type:login favorite:true reprompt:true otp:true attachments:true passkeys:true".length,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight(
                "type:login favorite:true reprompt:true otp:true attachments:true passkeys:true",
                VaultRoute.Args.SearchBy.ALL,
            ).spans,
        )
    }

    @Test
    fun `highlights negated clause and operator separately`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 10,
                    role = QueryHighlightRole.TextClause,
                ),
                QueryHighlightSpan(
                    start = 0,
                    end = 1,
                    role = QueryHighlightRole.Negation,
                ),
            ),
            highlighter.highlight("-note:temp", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights quoted value separately`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 15,
                    role = QueryHighlightRole.TextClause,
                ),
                QueryHighlightSpan(
                    start = 5,
                    end = 15,
                    role = QueryHighlightRole.QuotedValue,
                ),
            ),
            highlighter.highlight("note:\"temp pin\"", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights unknown qualifier as text clause`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 13,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("unknown:value", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights brand qualifier as plain text`() {
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = 10,
                    role = QueryHighlightRole.TextClause,
                ),
            ),
            highlighter.highlight("brand:visa", VaultRoute.Args.SearchBy.ALL).spans,
        )
    }

    @Test
    fun `highlights parser diagnostics`() {
        val missingValueSpans = highlighter
            .highlight("username:", VaultRoute.Args.SearchBy.ALL)
            .spans
        val unterminatedQuoteSpans = highlighter
            .highlight("\"oops", VaultRoute.Args.SearchBy.ALL)
            .spans

        assertTrue(missingValueSpans.any { it.role == QueryHighlightRole.Diagnostic })
        assertTrue(unterminatedQuoteSpans.any { it.role == QueryHighlightRole.Diagnostic })
        assertEquals(
            listOf(QueryHighlightRole.Diagnostic),
            unterminatedQuoteSpans.map { it.role },
        )
    }

    @Test
    fun `highlights localized facet qualifiers`() {
        val folderHighlighting = highlighter.highlight(
            query = "dossier:work",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            qualifierCatalog = localizedQualifierCatalog,
        )
        val organizationHighlighting = highlighter.highlight(
            query = "organisation:acme",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            qualifierCatalog = localizedQualifierCatalog,
        )
        val accountHighlighting = highlighter.highlight(
            query = "compte:me",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            qualifierCatalog = localizedQualifierCatalog,
        )

        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = "dossier:work".length,
                    role = QueryHighlightRole.FacetClause,
                ),
            ),
            folderHighlighting.spans,
        )
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = "organisation:acme".length,
                    role = QueryHighlightRole.FacetClause,
                ),
            ),
            organizationHighlighting.spans,
        )
        assertEquals(
            listOf(
                QueryHighlightSpan(
                    start = 0,
                    end = "compte:me".length,
                    role = QueryHighlightRole.FacetClause,
                ),
            ),
            accountHighlighting.spans,
        )
    }
}
