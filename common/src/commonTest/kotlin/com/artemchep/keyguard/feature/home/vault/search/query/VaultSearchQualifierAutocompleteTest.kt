package com.artemchep.keyguard.feature.home.vault.search.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultSearchQualifierAutocompleteTest {
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
    fun `suggests field qualifier for three character prefix`() {
        val suggestion = bestVaultSearchQualifierSuggestion("fie")

        assertEquals("field:", suggestion?.text)
    }

    @Test
    fun `applies qualifier suggestion to trailing fragment`() {
        val suggestion = requireNotNull(bestVaultSearchQualifierSuggestion("foo fie"))
        val result = applyVaultSearchQualifierSuggestion("foo fie", suggestion)

        assertEquals(
            "foo field:",
            result.text,
        )
        assertEquals(
            "foo field:".length,
            result.cursor,
        )
    }

    @Test
    fun `preserves leading negation when applying suggestion`() {
        val suggestion = requireNotNull(bestVaultSearchQualifierSuggestion("-fie"))
        val result = applyVaultSearchQualifierSuggestion("-fie", suggestion)

        assertEquals(
            "-field:",
            result.text,
        )
        assertEquals(
            "-field:".length,
            result.cursor,
        )
    }

    @Test
    fun `applies qualifier suggestion with cursor after colon`() {
        val suggestion = requireNotNull(bestVaultSearchQualifierSuggestion("fie"))
        val result = applyVaultSearchQualifierSuggestion("fie", suggestion)

        assertEquals(
            "field:",
            result.text,
        )
        assertEquals(
            6,
            result.cursor,
        )
    }

    @Test
    fun `does not suggest for fragments shorter than three characters`() {
        assertNull(bestVaultSearchQualifierSuggestion("fi"))
        assertNull(bestVaultSearchQualifierSuggestion("-fi"))
    }

    @Test
    fun `suggests canonical qualifier even for exact qualifier text`() {
        val suggestion = bestVaultSearchQualifierSuggestion("field")

        assertEquals("field:", suggestion?.text)
    }

    @Test
    fun `does not suggest once qualifier separator is present`() {
        assertNull(bestVaultSearchQualifierSuggestion("field:"))
    }

    @Test
    fun `does not suggest for unknown prefixes`() {
        assertNull(bestVaultSearchQualifierSuggestion("zzz"))
    }

    @Test
    fun `does not suggest removed qualifiers`() {
        assertNull(bestVaultSearchQualifierSuggestion("typ"))
        assertNull(bestVaultSearchQualifierSuggestion("fav"))
        assertNull(bestVaultSearchQualifierSuggestion("rep"))
        assertNull(bestVaultSearchQualifierSuggestion("otp"))
        assertNull(bestVaultSearchQualifierSuggestion("attachments"))
        assertNull(bestVaultSearchQualifierSuggestion("passkeys"))
    }

    @Test
    fun `suggests attachment qualifier for three character prefix`() {
        val suggestion = bestVaultSearchQualifierSuggestion("att")

        assertEquals("attachment:", suggestion?.text)
    }

    @Test
    fun `suggests passkey qualifier for three character prefix`() {
        val suggestion = bestVaultSearchQualifierSuggestion("pas")

        assertEquals("passkey:", suggestion?.text)
    }

    @Test
    fun `suggests ssh qualifier for exact prefix`() {
        val suggestion = bestVaultSearchQualifierSuggestion("ssh")

        assertEquals("ssh:", suggestion?.text)
    }

    @Test
    fun `prefers canonical qualifier labels over aliases`() {
        assertEquals(
            "domain:",
            bestVaultSearchQualifierSuggestion("host")?.text,
        )

        val hostSuggestion = requireNotNull(bestVaultSearchQualifierSuggestion("host"))
        val hostResult = applyVaultSearchQualifierSuggestion("host", hostSuggestion)
        assertEquals("domain:", hostResult.text)
        assertEquals("domain:".length, hostResult.cursor)
    }

    @Test
    fun `does not suggest removed brand alias`() {
        assertNull(bestVaultSearchQualifierSuggestion("brand"))
    }

    @Test
    fun `suggests canonical folder qualifier for localized prefix`() {
        val suggestion = bestVaultSearchQualifierSuggestion(
            query = "doss",
            catalog = localizedQualifierCatalog,
        )

        assertEquals("folder:", suggestion?.text)
    }

    @Test
    fun `applies canonical qualifier text after localized suggestion`() {
        val suggestion = requireNotNull(
            bestVaultSearchQualifierSuggestion(
                query = "doss",
                catalog = localizedQualifierCatalog,
            ),
        )
        val result = applyVaultSearchQualifierSuggestion("doss", suggestion)

        assertEquals("folder:", result.text)
        assertEquals("folder:".length, result.cursor)
    }
}
