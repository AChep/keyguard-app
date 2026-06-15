package com.artemchep.keyguard.feature.home.vault.search.query.model

data class SourceSpan(
    val start: Int,
    val end: Int,
) {
    init {
        require(start <= end) { "Invalid span [$start, $end)" }
    }

    val length: Int
        get() = end - start
}

enum class DiagnosticSeverity {
    Warning,
    Error,
}

data class ParseDiagnostic(
    val message: String,
    val span: SourceSpan,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
)

sealed interface QueryValueNode {
    val value: String
    val span: SourceSpan
    val quoted: Boolean
}

data class TextValueNode(
    override val value: String,
    override val span: SourceSpan,
) : QueryValueNode {
    override val quoted: Boolean = false
}

data class QuotedValueNode(
    override val value: String,
    override val span: SourceSpan,
) : QueryValueNode {
    override val quoted: Boolean = true
}

sealed interface ClauseNode {
    val raw: String
    val span: SourceSpan
}

data class TextTermNode(
    val value: QueryValueNode,
    override val raw: String,
    override val span: SourceSpan,
) : ClauseNode

data class QualifiedTermNode(
    val qualifier: String,
    val qualifierSpan: SourceSpan,
    val value: QueryValueNode?,
    override val raw: String,
    override val span: SourceSpan,
) : ClauseNode

data class NegatedNode(
    val clause: ClauseNode,
    val operatorSpan: SourceSpan,
    override val raw: String,
    override val span: SourceSpan,
) : ClauseNode

data class ParsedQuery(
    val source: String,
    val clauses: List<ClauseNode>,
    val diagnostics: List<ParseDiagnostic> = emptyList(),
) {
    val hasClauses: Boolean
        get() = clauses.isNotEmpty()
}
