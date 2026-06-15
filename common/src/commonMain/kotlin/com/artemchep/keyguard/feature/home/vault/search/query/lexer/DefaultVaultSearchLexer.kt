package com.artemchep.keyguard.feature.home.vault.search.query.lexer

import com.artemchep.keyguard.feature.home.vault.search.query.model.DiagnosticSeverity
import com.artemchep.keyguard.feature.home.vault.search.query.model.ParseDiagnostic
import com.artemchep.keyguard.feature.home.vault.search.query.model.SourceSpan

class DefaultVaultSearchLexer : VaultSearchLexer {
    override fun lex(query: String): LexedQuery {
        val tokens = mutableListOf<SearchToken>()
        val diagnostics = mutableListOf<ParseDiagnostic>()
        var index = 0
        var atClauseStart = true

        while (index < query.length) {
            val char = query[index]
            if (char.isWhitespace()) {
                val start = index
                while (index < query.length && query[index].isWhitespace()) {
                    index += 1
                }
                tokens += SearchToken.Whitespace(
                    span = SourceSpan(start, index),
                )
                atClauseStart = true
                continue
            }

            if (char == ':') {
                tokens += SearchToken.Colon(
                    span = SourceSpan(index, index + 1),
                )
                index += 1
                atClauseStart = false
                continue
            }

            if (char == '-' && atClauseStart) {
                tokens += SearchToken.Minus(
                    span = SourceSpan(index, index + 1),
                )
                index += 1
                atClauseStart = false
                continue
            }

            if (char == '"') {
                val start = index
                index += 1
                val buffer = StringBuilder()
                var closed = false
                while (index < query.length) {
                    val current = query[index]
                    when {
                        current == '\\' && index + 1 < query.length -> {
                            val escaped = query[index + 1]
                            if (escaped == '\\' || escaped == '"') {
                                buffer.append(escaped)
                                index += 2
                            } else {
                                buffer.append(current)
                                index += 1
                            }
                        }

                        current == '"' -> {
                            index += 1
                            closed = true
                            break
                        }

                        else -> {
                            buffer.append(current)
                            index += 1
                        }
                    }
                }
                val span = SourceSpan(start, index)
                if (!closed) {
                    diagnostics += ParseDiagnostic(
                        message = "Unterminated quoted value.",
                        span = span,
                        severity = DiagnosticSeverity.Error,
                    )
                }
                tokens += SearchToken.Text(
                    value = buffer.toString(),
                    span = span,
                    quoted = true,
                )
                atClauseStart = false
                continue
            }

            val start = index
            while (index < query.length) {
                val current = query[index]
                val shouldBreak = current.isWhitespace() || current == ':'
                if (shouldBreak) {
                    break
                }
                index += 1
            }
            tokens += SearchToken.Text(
                value = query.substring(start, index),
                span = SourceSpan(start, index),
                quoted = false,
            )
            atClauseStart = false
        }

        return LexedQuery(
            source = query,
            tokens = tokens,
            diagnostics = diagnostics,
        )
    }
}
