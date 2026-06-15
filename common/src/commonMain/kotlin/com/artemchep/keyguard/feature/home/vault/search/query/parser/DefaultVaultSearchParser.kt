package com.artemchep.keyguard.feature.home.vault.search.query.parser

import com.artemchep.keyguard.feature.home.vault.search.query.lexer.DefaultVaultSearchLexer
import com.artemchep.keyguard.feature.home.vault.search.query.lexer.LexedQuery
import com.artemchep.keyguard.feature.home.vault.search.query.lexer.SearchToken
import com.artemchep.keyguard.feature.home.vault.search.query.lexer.VaultSearchLexer
import com.artemchep.keyguard.feature.home.vault.search.query.model.ClauseNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.NegatedNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParseDiagnostic
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParsedQuery
import com.artemchep.keyguard.feature.home.vault.search.query.model.QueryValueNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.QualifiedTermNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.QuotedValueNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.SourceSpan
import com.artemchep.keyguard.feature.home.vault.search.query.model.TextTermNode
import com.artemchep.keyguard.feature.home.vault.search.query.model.TextValueNode

class DefaultVaultSearchParser(
    private val lexer: VaultSearchLexer = DefaultVaultSearchLexer(),
) : VaultSearchParser {
    override fun parse(query: String): ParsedQuery {
        val lexed = lexer.lex(query)
        val diagnostics = lexed.diagnostics.toMutableList()
        val clauses = mutableListOf<ClauseNode>()
        var start = 0
        while (start < lexed.tokens.size) {
            while (start < lexed.tokens.size && lexed.tokens[start] is SearchToken.Whitespace) {
                start += 1
            }
            if (start >= lexed.tokens.size) {
                break
            }
            var end = start
            while (end < lexed.tokens.size && lexed.tokens[end] !is SearchToken.Whitespace) {
                end += 1
            }
            parseClause(
                lexed = lexed,
                tokens = lexed.tokens.subList(start, end),
                diagnostics = diagnostics,
            )?.let(clauses::add)
            start = end + 1
        }

        return ParsedQuery(
            source = query,
            clauses = clauses,
            diagnostics = diagnostics,
        )
    }

    private fun parseClause(
        lexed: LexedQuery,
        tokens: List<SearchToken>,
        diagnostics: MutableList<ParseDiagnostic>,
    ): ClauseNode? {
        if (tokens.isEmpty()) {
            return null
        }

        val clauseStart = tokens.first().span.start
        val clauseEnd = tokens.last().span.end
        val clauseRaw = lexed.source.substring(clauseStart, clauseEnd)

        var index = 0
        val negationToken = tokens.firstOrNull() as? SearchToken.Minus
        if (negationToken != null) {
            index += 1
        }
        if (index >= tokens.size) {
            diagnostics += ParseDiagnostic(
                message = "Missing clause after negation.",
                span = negationToken!!.span,
            )
            return null
        }

        val qualifierToken = tokens.getOrNull(index) as? SearchToken.Text
            ?: return null
        val colonToken = tokens.getOrNull(index + 1) as? SearchToken.Colon
        val node = if (colonToken != null) {
            val valueTokens = tokens.subList(index + 2, tokens.size)
            if (valueTokens.isEmpty()) {
                diagnostics += ParseDiagnostic(
                    message = "Missing value after qualifier.",
                    span = SourceSpan(
                        start = qualifierToken.span.start,
                        end = colonToken.span.end,
                    ),
                )
                QualifiedTermNode(
                    qualifier = qualifierToken.value,
                    qualifierSpan = qualifierToken.span,
                    value = null,
                    raw = clauseRaw,
                    span = SourceSpan(clauseStart, clauseEnd),
                )
            } else {
                QualifiedTermNode(
                    qualifier = qualifierToken.value,
                    qualifierSpan = qualifierToken.span,
                    value = parseValueNode(
                        source = lexed.source,
                        tokens = valueTokens,
                    ),
                    raw = clauseRaw,
                    span = SourceSpan(clauseStart, clauseEnd),
                )
            }
        } else {
            TextTermNode(
                value = parseValueNode(
                    source = lexed.source,
                    tokens = tokens.subList(index, tokens.size),
                ),
                raw = clauseRaw,
                span = SourceSpan(clauseStart, clauseEnd),
            )
        }

        return if (negationToken != null) {
            NegatedNode(
                clause = node,
                operatorSpan = negationToken.span,
                raw = clauseRaw,
                span = SourceSpan(clauseStart, clauseEnd),
            )
        } else {
            node
        }
    }

    private fun parseValueNode(
        source: String,
        tokens: List<SearchToken>,
    ): QueryValueNode {
        val first = tokens.first()
        val last = tokens.last()
        val span = SourceSpan(first.span.start, last.span.end)
        val textToken = first as? SearchToken.Text
        if (tokens.size == 1 && textToken != null) {
            return if (textToken.quoted) {
                QuotedValueNode(
                    value = textToken.value,
                    span = span,
                )
            } else {
                TextValueNode(
                    value = textToken.value,
                    span = span,
                )
            }
        }
        val raw = source.substring(span.start, span.end)
        return TextValueNode(
            value = raw,
            span = span,
        )
    }
}
