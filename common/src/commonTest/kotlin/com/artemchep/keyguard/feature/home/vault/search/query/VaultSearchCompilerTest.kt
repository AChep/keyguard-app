package com.artemchep.keyguard.feature.home.vault.search.query

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledColdTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledFacetClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledHotTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultFacetField
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultSearchCompilerTest {
    private val parser = DefaultVaultSearchParser()
    private val compiler = DefaultVaultSearchQueryCompiler(DefaultSearchTokenizer())
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
    fun `rewrites bare terms to password field when searchBy password`() {
        val parsed = parser.parse("alpha")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.PASSWORD)

        val clause = assertIs<CompiledColdTextClause>(plan.positiveClauses.single())
        assertEquals(VaultTextField.Password, clause.field)
    }

    @Test
    fun `keeps unknown qualifiers as plain text clause`() {
        val parsed = parser.parse("unknown:alpha")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        assertEquals(1, plan.positiveClauses.size)
        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertTrue(VaultTextField.Title in clause.fields)
        assertEquals(listOf("unknown", "alpha"), clause.tokenization.terms)
    }

    @Test
    fun `keeps unknown qualifiers in password search as password clause`() {
        val parsed = parser.parse("unknown:alpha")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.PASSWORD)

        assertEquals(1, plan.positiveClauses.size)
        val clause = assertIs<CompiledColdTextClause>(plan.positiveClauses.single())
        assertEquals(VaultTextField.Password, clause.field)
        assertEquals(listOf("unknown", "alpha"), clause.tokenization.terms)
    }

    @Test
    fun `keeps url-like free text queries intact`() {
        val parsed = parser.parse("https://example.com")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        assertEquals(1, plan.positiveClauses.size)
        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertTrue(VaultTextField.Url in clause.fields)
        assertEquals(
            listOf("https", "example", "com"),
            clause.tokenization.terms,
        )
    }

    @Test
    fun `bare search all term includes username email custom field names and attachment names but not card brand`() {
        val parsed = parser.parse("alpha")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertTrue(VaultTextField.Username in clause.fields)
        assertTrue(VaultTextField.Email in clause.fields)
        assertTrue(VaultTextField.FieldName in clause.fields)
        assertTrue(clause.fields.any { it.name == "AttachmentName" })
        assertFalse(VaultTextField.CardBrand in clause.fields)
    }

    @Test
    fun `keeps field qualifier as cold field clause`() {
        val parsed = parser.parse("field:alpha")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledColdTextClause>(plan.positiveClauses.single())
        assertEquals(VaultTextField.Field, clause.field)
    }

    @Test
    fun `compiles attachment qualifier as text clause`() {
        val parsed = parser.parse("attachment:file")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertEquals(setOf("AttachmentName"), clause.fields.map { it.name }.toSet())
    }

    @Test
    fun `compiles passkey qualifier as text clause`() {
        val parsed = parser.parse("passkey:device")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertEquals(
            setOf("PasskeyRpId", "PasskeyDisplayName"),
            clause.fields.map { it.name }.toSet(),
        )
    }

    @Test
    fun `compiles ssh qualifier as cold field clause`() {
        val parsed = parser.parse("ssh:fingerprint")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledColdTextClause>(plan.positiveClauses.single())
        assertEquals("Ssh", clause.field.name)
    }

    @Test
    fun `compiles card brand qualifier as text clause`() {
        val parsed = parser.parse("card-brand:visa")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertEquals(setOf(VaultTextField.CardBrand), clause.fields)
    }

    @Test
    fun `brand qualifier falls back to plain text`() {
        val parsed = parser.parse("brand:visa")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertFalse(VaultTextField.CardBrand in clause.fields)
    }

    @Test
    fun `keeps host qualifier as domain alias`() {
        val parsed = parser.parse("host:example.com")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        val clause = assertIs<CompiledHotTextClause>(plan.positiveClauses.single())
        assertEquals(
            setOf(
                VaultTextField.Host,
                VaultTextField.PasskeyRpId,
            ),
            clause.fields,
        )
    }

    @Test
    fun `compiles facet clauses`() {
        val parsed = parser.parse("folder:work tag:ops")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        assertEquals(2, plan.positiveClauses.size)
        assertIs<CompiledFacetClause>(plan.positiveClauses[0])
        assertIs<CompiledFacetClause>(plan.positiveClauses[1])
    }

    @Test
    fun `removed qualifiers fall back to plain text clauses`() {
        val parsed = parser.parse("type:login favorite:true reprompt:true otp:true attachments:true passkeys:true")
        val plan = compiler.compile(parsed, VaultRoute.Args.SearchBy.ALL)

        assertEquals(6, plan.positiveClauses.size)
        plan.positiveClauses.forEach { clause ->
            assertIs<CompiledHotTextClause>(clause)
        }
    }

    @Test
    fun `compiles localized facet qualifiers as canonical fields`() {
        val folderPlan = compiler.compile(
            query = parser.parse("dossier:work"),
            searchBy = VaultRoute.Args.SearchBy.ALL,
            qualifierCatalog = localizedQualifierCatalog,
        )
        val organizationPlan = compiler.compile(
            query = parser.parse("organisation:acme"),
            searchBy = VaultRoute.Args.SearchBy.ALL,
            qualifierCatalog = localizedQualifierCatalog,
        )
        val accountPlan = compiler.compile(
            query = parser.parse("compte:me"),
            searchBy = VaultRoute.Args.SearchBy.ALL,
            qualifierCatalog = localizedQualifierCatalog,
        )

        assertEquals(
            VaultFacetField.Folder,
            assertIs<CompiledFacetClause>(folderPlan.positiveClauses.single()).field,
        )
        assertEquals(
            VaultFacetField.Organization,
            assertIs<CompiledFacetClause>(organizationPlan.positiveClauses.single()).field,
        )
        assertEquals(
            VaultFacetField.Account,
            assertIs<CompiledFacetClause>(accountPlan.positiveClauses.single()).field,
        )
    }
}
