package com.artemchep.keyguard.feature.home.vault.search.query.highlight

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.defaultVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultQueryClauseSemanticKind
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.semanticKindForHighlight
import com.artemchep.keyguard.feature.home.vault.search.query.model.ClauseNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.NegatedNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.QualifiedTermNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.QueryValueNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.SourceSpan
import com.artemchep.keyguard.feature.home.vault.search.query.parser.VaultSearchParser
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Immutable
data class QueryHighlighting(
    val spans: List<QueryHighlightSpan> = emptyList(),
) {
    companion object {
        val Empty = QueryHighlighting()
    }

    val isEmpty: Boolean
        get() = spans.isEmpty()
}

@Immutable
data class QueryHighlightSpan(
    val start: Int,
    val end: Int,
    val role: QueryHighlightRole,
) {
    init {
        require(start <= end) { "Invalid span [$start, $end)" }
    }
}

@Immutable
enum class QueryHighlightRole {
    TextClause,
    FacetClause,
    BooleanClause,
    Negation,
    QuotedValue,
    UnsupportedQualifier,
    Diagnostic,
}

interface VaultSearchQueryHighlighter {
    fun highlight(
        query: String,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog = defaultVaultSearchQualifierCatalog,
    ): QueryHighlighting
}

class DefaultVaultSearchQueryHighlighter(
    private val parser: VaultSearchParser,
) : VaultSearchQueryHighlighter {
    constructor(directDI: DirectDI) : this(
        parser = directDI.instance(),
    )

    override fun highlight(
        query: String,
        searchBy: VaultRoute.Args.SearchBy,
        qualifierCatalog: VaultSearchQualifierCatalog,
    ): QueryHighlighting {
        if (query.isEmpty()) {
            return QueryHighlighting.Empty
        }

        val parsed = parser.parse(query)
        val spans = buildList {
            parsed.clauses.forEach { clause ->
                clause.highlightRole(
                    searchBy = searchBy,
                    qualifierCatalog = qualifierCatalog,
                )
                    ?.let { role ->
                        add(
                            QueryHighlightSpan(
                                start = clause.baseSpan()!!.start,
                                end = clause.baseSpan()!!.end,
                                role = role,
                            ),
                        )
                        clause.negationSpan()
                            ?.let { span ->
                                add(
                                    QueryHighlightSpan(
                                        start = span.start,
                                        end = span.end,
                                        role = QueryHighlightRole.Negation,
                                    ),
                                )
                            }
                        clause.quotedValueSpan()
                            ?.let { span ->
                                add(
                                    QueryHighlightSpan(
                                        start = span.start,
                                        end = span.end,
                                        role = QueryHighlightRole.QuotedValue,
                                    ),
                                )
                            }
                    }
            }
            parsed.diagnostics.forEach { diagnostic ->
                add(
                    QueryHighlightSpan(
                        start = diagnostic.span.start,
                        end = diagnostic.span.end,
                        role = QueryHighlightRole.Diagnostic,
                    ),
                )
            }
        }
            .sortedWith(
                compareBy<QueryHighlightSpan>(
                    QueryHighlightSpan::priority,
                    QueryHighlightSpan::start,
                    QueryHighlightSpan::end,
                ),
            )

        return QueryHighlighting(
            spans = spans,
        )
    }
}

private fun ClauseNode.highlightRole(
    searchBy: VaultRoute.Args.SearchBy,
    qualifierCatalog: VaultSearchQualifierCatalog,
): QueryHighlightRole? {
    val actualClause = when (this) {
        is NegatedNode -> clause
        else -> this
    }
    return when (actualClause) {
        is QualifiedTermNode -> actualClause.semanticKindForHighlight(
            searchBy = searchBy,
            qualifierCatalog = qualifierCatalog,
        ).toHighlightRole()
        else -> null
    }
}

private fun QueryHighlightSpan.priority(): Int = when (role) {
    QueryHighlightRole.TextClause,
    QueryHighlightRole.FacetClause,
    QueryHighlightRole.BooleanClause,
    QueryHighlightRole.UnsupportedQualifier,
        -> 0

    QueryHighlightRole.Negation -> 1
    QueryHighlightRole.QuotedValue -> 2
    QueryHighlightRole.Diagnostic -> 3
}

private fun ClauseNode.baseSpan(): SourceSpan? {
    val actualClause = when (this) {
        is NegatedNode -> clause
        else -> this
    }
    return when (actualClause) {
        is QualifiedTermNode -> actualClause.span
        else -> null
    }
}

private fun ClauseNode.negationSpan(): SourceSpan? {
    val actualClause = when (this) {
        is NegatedNode -> clause
        else -> return null
    }
    return when (actualClause) {
        is QualifiedTermNode -> operatorSpan
        else -> null
    }
}

private fun ClauseNode.quotedValueSpan(): SourceSpan? {
    val actualClause = when (this) {
        is NegatedNode -> clause
        else -> this
    }
    val value = when (actualClause) {
        is QualifiedTermNode -> actualClause.value
        else -> null
    }
    return value
        ?.takeIf(QueryValueNode::quoted)
        ?.span
}

private fun VaultQueryClauseSemanticKind.toHighlightRole(): QueryHighlightRole = when (this) {
    VaultQueryClauseSemanticKind.Text -> QueryHighlightRole.TextClause
    VaultQueryClauseSemanticKind.Facet -> QueryHighlightRole.FacetClause
    VaultQueryClauseSemanticKind.Boolean -> QueryHighlightRole.BooleanClause
    VaultQueryClauseSemanticKind.Unsupported -> QueryHighlightRole.UnsupportedQualifier
}
