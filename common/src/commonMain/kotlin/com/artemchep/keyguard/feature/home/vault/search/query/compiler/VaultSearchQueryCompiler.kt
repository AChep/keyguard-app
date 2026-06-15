package com.artemchep.keyguard.feature.home.vault.search.query.compiler

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.engine.SearchTokenization
import com.artemchep.keyguard.feature.home.vault.search.engine.SearchTokenizerProfile
import com.artemchep.keyguard.feature.home.vault.search.engine.profile
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.defaultVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.findVaultSearchQualifierDefinition
import com.artemchep.keyguard.feature.home.vault.search.query.model.ClauseNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.NegatedNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParseDiagnostic
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParsedQuery
import com.artemchep.keyguard.feature.home.vault.search.query.model.QualifiedTermNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.TextTermNode

/**
 * Enumerates vault fields that participate in text search clauses.
 *
 * These fields map to tokenizer profiles and compile into hot or cold text matching.
 */
enum class VaultTextField(
    val displayName: String,
) {
    Title("title"),
    Url("url"),
    Host("domain"),
    Username("username"),
    Email("email"),
    PasskeyRpId("passkey"),
    IdentityName("name"),
    Phone("phone"),
    CardholderName("cardholder"),
    CardBrand("brand"),
    PasskeyDisplayName("passkey"),
    AttachmentName("attachment"),
    FieldName("field"),
    Note("note"),
    Field("field"),
    Ssh("ssh"),
    Password("password"),
    CardNumber("card-number"),
}

/**
 * Enumerates categorical vault fields matched through facet-style filtering.
 *
 * These fields are normalized and resolved as exact metadata filters instead of scored text.
 */
enum class VaultFacetField {
    Type,
    Account,
    Folder,
    Tag,
    Organization,
    Collection,
}

/**
 * Enumerates boolean vault flags exposed as query qualifiers.
 *
 * These fields compile into boolean clauses that accept explicit `true` and `false` values.
 */
enum class VaultBooleanField {
    Favorite,
    Reprompt,
    Otp,
    Attachments,
    Passkeys,
}

sealed interface CompiledQueryClause {
    val raw: String
}

data class CompiledHotTextClause(
    val fields: Set<VaultTextField>,
    val tokenizations: Map<SearchTokenizerProfile, SearchTokenization>,
    val rawPhrase: String? = null,
    override val raw: String,
) : CompiledQueryClause {
    val tokenization: SearchTokenization
        get() =
            tokenizations[SearchTokenizerProfile.TEXT]
                ?: tokenizations.values.first()

    fun tokenizationFor(field: VaultTextField): SearchTokenization = tokenizations.getValue(field.profile())
}

data class CompiledColdTextClause(
    val field: VaultTextField,
    val tokenization: SearchTokenization,
    val rawPhrase: String? = null,
    override val raw: String,
) : CompiledQueryClause

data class CompiledFacetClause(
    val field: VaultFacetField,
    val values: Set<String>,
    override val raw: String,
) : CompiledQueryClause

data class CompiledBooleanClause(
    val field: VaultBooleanField,
    val value: Boolean,
    override val raw: String,
) : CompiledQueryClause

data class CompiledQueryPlan(
    val rawQuery: String,
    val parsedQuery: ParsedQuery,
    val positiveClauses: List<CompiledQueryClause>,
    val negativeClauses: List<CompiledQueryClause>,
    val diagnostics: List<ParseDiagnostic> = parsedQuery.diagnostics,
) {
    val hasScoringClauses: Boolean =
        positiveClauses.any {
            it is CompiledHotTextClause || it is CompiledColdTextClause
        } ||
            negativeClauses.any {
                it is CompiledHotTextClause || it is CompiledColdTextClause
            }

    val hasActiveClauses: Boolean
        get() = positiveClauses.isNotEmpty() || negativeClauses.isNotEmpty()

    val id: Int =
        31 * rawQuery.hashCode() +
            31 * positiveClauses.hashCode() +
            negativeClauses.hashCode()
}

interface VaultSearchQueryCompiler {
    fun compile(
        query: ParsedQuery,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog = defaultVaultSearchQualifierCatalog,
    ): CompiledQueryPlan
}

/**
 * Classifies query clauses for qualifier metadata and syntax highlighting.
 *
 * The semantic kind distinguishes text, facet, boolean, and unsupported clauses.
 */
internal enum class VaultQueryClauseSemanticKind {
    Text,
    Facet,
    Boolean,
    Unsupported,
}

internal fun ClauseNode.semanticKindForHighlight(
    searchBy: VaultRoute.Args.SearchBy,
    qualifierCatalog: VaultSearchQualifierCatalog = defaultVaultSearchQualifierCatalog,
): VaultQueryClauseSemanticKind {
    val actualClause =
        when (this) {
            is NegatedNode -> clause
            else -> this
        }
    return when (actualClause) {
        is TextTermNode -> {
            VaultQueryClauseSemanticKind.Text
        }

        is QualifiedTermNode -> {
            qualifierSemanticKindForHighlight(
                qualifier = actualClause.qualifier,
                searchBy = searchBy,
                qualifierCatalog = qualifierCatalog,
            )
        }

        else -> {
            VaultQueryClauseSemanticKind.Unsupported
        }
    }
}

private fun qualifierSemanticKindForHighlight(
    qualifier: String,
    searchBy: VaultRoute.Args.SearchBy,
    qualifierCatalog: VaultSearchQualifierCatalog,
): VaultQueryClauseSemanticKind =
    findVaultSearchQualifierDefinition(
        qualifier = qualifier,
        catalog = qualifierCatalog,
    )
        ?.metadata
        ?.semanticKind
        ?: when (searchBy) {
            VaultRoute.Args.SearchBy.ALL,
            VaultRoute.Args.SearchBy.PASSWORD,
            -> VaultQueryClauseSemanticKind.Text
        }
