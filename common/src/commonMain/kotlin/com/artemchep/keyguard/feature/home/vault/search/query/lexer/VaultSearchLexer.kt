package com.artemchep.keyguard.feature.home.vault.search.query.lexer

import com.artemchep.keyguard.feature.home.vault.search.query.model.ParseDiagnostic
import com.artemchep.keyguard.feature.home.vault.search.query.model.SourceSpan

sealed interface SearchToken {
    val span: SourceSpan

    data class Text(
        val value: String,
        override val span: SourceSpan,
        val quoted: Boolean,
    ) : SearchToken

    data class Colon(
        override val span: SourceSpan,
    ) : SearchToken

    data class Minus(
        override val span: SourceSpan,
    ) : SearchToken

    data class Whitespace(
        override val span: SourceSpan,
    ) : SearchToken
}

data class LexedQuery(
    val source: String,
    val tokens: List<SearchToken>,
    val diagnostics: List<ParseDiagnostic> = emptyList(),
)

interface VaultSearchLexer {
    fun lex(query: String): LexedQuery
}
