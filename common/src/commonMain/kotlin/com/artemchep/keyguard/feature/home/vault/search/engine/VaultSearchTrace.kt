package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledBooleanClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledColdTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledFacetClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledHotTextClause
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.CompiledQueryClause
import kotlin.math.roundToInt

internal const val VAULT_SEARCH_SURFACE_VAULT_LIST = "vault-list"
internal const val VAULT_SEARCH_SURFACE_QUICK_SEARCH = "quick-search"

private const val TAG_INDEX = "VaultSearch/Index"
private const val TAG_QUERY = "VaultSearch/Query"
private const val TAG_EVAL = "VaultSearch/Eval"
private const val TAG_TRACE = "VaultSearch/Trace"

internal interface VaultSearchTraceSink {
    val isEnabled: Boolean

    fun index(event: IndexTraceEvent)

    fun query(event: QueryTraceEvent)

    fun evaluation(event: EvaluationTraceEvent)

    fun item(event: ItemTraceEvent)

    fun surface(event: SurfaceTraceEvent)
}

internal object NoOpVaultSearchTraceSink : VaultSearchTraceSink {
    override val isEnabled: Boolean = false

    override fun index(event: IndexTraceEvent) = Unit

    override fun query(event: QueryTraceEvent) = Unit

    override fun evaluation(event: EvaluationTraceEvent) = Unit

    override fun item(event: ItemTraceEvent) = Unit

    override fun surface(event: SurfaceTraceEvent) = Unit
}

internal class DevVaultSearchTraceSink(
    private val logRepository: LogRepository,
) : VaultSearchTraceSink {
    override val isEnabled: Boolean = true

    override fun index(event: IndexTraceEvent) {
        logRepository.postDebug(TAG_INDEX) {
            event.render()
        }
    }

    override fun query(event: QueryTraceEvent) {
        logRepository.postDebug(TAG_QUERY) {
            event.render()
        }
    }

    override fun evaluation(event: EvaluationTraceEvent) {
        logRepository.postDebug(TAG_EVAL) {
            event.render()
        }
    }

    override fun item(event: ItemTraceEvent) {
        logRepository.postDebug(TAG_TRACE) {
            event.render()
        }
    }

    override fun surface(event: SurfaceTraceEvent) {
        logRepository.postDebug(TAG_EVAL) {
            event.render()
        }
    }
}

internal enum class IndexTracePhase {
    Start,
    Finish,
}

internal data class IndexTraceEvent(
    val surface: String? = null,
    val phase: IndexTracePhase,
    val itemCount: Int,
    val accountCount: Int,
    val folderCount: Int,
    val tagCount: Int,
    val collectionCount: Int,
    val organizationCount: Int,
    val documentCount: Int? = null,
    val hotPostingCounts: Map<String, Int> = emptyMap(),
    val reusedDocumentCount: Int = 0,
    val rebuiltDocumentCount: Int = 0,
    val removedDocumentCount: Int = 0,
    val durationMs: Long? = null,
)

internal data class QueryTraceEvent(
    val surface: String? = null,
    val rawQuery: String,
    val searchBy: VaultRoute.Args.SearchBy,
    val parsedClauses: List<String>,
    val diagnostics: List<String>,
    val positiveClauses: List<String>,
    val negativeClauses: List<String>,
    val planId: Int?,
    val hasActiveClauses: Boolean,
    val durationMs: Long,
)

internal data class EvaluationTraceEvent(
    val surface: String? = null,
    val rawQuery: String,
    val planId: Int,
    val rankingMode: String,
    val initialCandidateCount: Int,
    val afterFacetCount: Int,
    val afterBooleanCount: Int,
    val afterHotCount: Int,
    val afterColdCount: Int,
    val afterNegativeCount: Int,
    val finalResultCount: Int,
    val durationMs: Long,
)

internal enum class ItemTraceDisposition(
    val label: String,
) {
    Kept("kept"),
    DroppedByFacet("dropped-by-facet"),
    DroppedByBoolean("dropped-by-boolean"),
    DroppedByTextMiss("dropped-by-text-miss"),
    DroppedByNegativeClause("dropped-by-negative-clause"),
}

internal data class ItemClauseTrace(
    val clause: String,
    val kind: String,
    val stage: String,
    val matched: Boolean,
    val matchedField: String? = null,
    val matchedTermCount: Int = 0,
    val phraseMatched: Boolean = false,
    val scoreContribution: Double = 0.0,
    val fieldPresence: Boolean? = null,
    val fieldTokenCount: Int? = null,
)

internal data class ItemTraceEvent(
    val surface: String? = null,
    val rawQuery: String,
    val planId: Int,
    val itemId: String,
    val sourceId: String,
    val type: String,
    val accountId: String? = null,
    val folderId: String? = null,
    val disposition: ItemTraceDisposition,
    val clauses: List<ItemClauseTrace>,
)

internal data class SurfaceTraceEvent(
    val surface: String,
    val rawQuery: String,
    val rawItemCount: Int? = null,
    val routeFilteredCount: Int? = null,
    val filterFilteredCount: Int? = null,
    val preferredCount: Int? = null,
    val activeSort: String,
    val rankingMode: String,
    val finalResultCount: Int,
)

internal fun CompiledQueryClause.describeForTrace(): String = when (this) {
    is CompiledFacetClause -> "facet:${field.name.lowercase()}(${values.joinToString()})"
    is CompiledBooleanClause -> "boolean:${field.name.lowercase()}=$value"
    is CompiledHotTextClause -> "hot:${fields.joinToString { it.displayName }}(${tokenization.terms.joinToString()})"
    is CompiledColdTextClause -> "cold:${field.displayName}(${tokenization.terms.joinToString()})"
}

internal fun CompiledQueryClause.kindForTrace(): String = when (this) {
    is CompiledFacetClause -> "facet"
    is CompiledBooleanClause -> "boolean"
    is CompiledHotTextClause -> "hot"
    is CompiledColdTextClause -> "cold"
}

internal fun CompiledQueryClause.stageForTrace(): String = when (this) {
    is CompiledFacetClause -> "facet-filter"
    is CompiledBooleanClause -> "boolean-filter"
    is CompiledHotTextClause -> "text-match"
    is CompiledColdTextClause -> "cold-scan"
}

internal fun CompiledQueryClause.fieldForTrace(): String? = when (this) {
    is CompiledFacetClause -> field.name.lowercase()
    is CompiledBooleanClause -> field.name.lowercase()
    is CompiledHotTextClause -> fields.joinToString { it.displayName }
    is CompiledColdTextClause -> field.displayName
}

internal fun rankingModeForTrace(hasScoringClauses: Boolean): String =
    if (hasScoringClauses) {
        "query-score-first"
    } else {
        "sort-preserving"
    }

internal fun formatSortForTrace(
    sortId: String,
    reversed: Boolean = false,
    favorites: Boolean = false,
): String {
    val flags = buildList {
        if (reversed) add("reversed")
        if (favorites) add("favorites")
    }.joinToString(separator = ",")
    return if (flags.isBlank()) {
        sortId
    } else {
        "$sortId[$flags]"
    }
}

internal fun IndexTraceEvent.render(): String = buildString {
    append("surface=")
    append(surface.orUnknown())
    append(" phase=")
    append(phase.name.lowercase())
    append(" items=")
    append(itemCount)
    append(" metadata(accounts=")
    append(accountCount)
    append(", folders=")
    append(folderCount)
    append(", tags=")
    append(tagCount)
    append(", collections=")
    append(collectionCount)
    append(", organizations=")
    append(organizationCount)
    append(')')
    documentCount?.let {
        append(" documents=")
        append(it)
    }
    if (hotPostingCounts.isNotEmpty()) {
        append(" hotPostings=")
        append(
            hotPostingCounts.entries.joinToString { (field, count) ->
                "$field:$count"
            },
        )
    }
    durationMs?.let {
        append(" durationMs=")
        append(it)
    }
}

internal fun QueryTraceEvent.render(): String = buildString {
    append("surface=")
    append(surface.orUnknown())
    append(" query=")
    append(rawQuery.quote())
    append(" searchBy=")
    append(searchBy.name.lowercase())
    append(" parsed=[")
    append(parsedClauses.joinToString())
    append(']')
    if (diagnostics.isNotEmpty()) {
        append(" diagnostics=[")
        append(diagnostics.joinToString())
        append(']')
    }
    append(" positive=[")
    append(positiveClauses.joinToString())
    append(']')
    append(" negative=[")
    append(negativeClauses.joinToString())
    append(']')
    append(" active=")
    append(hasActiveClauses)
    append(" planId=")
    append(planId ?: "none")
    append(" durationMs=")
    append(durationMs)
}

internal fun EvaluationTraceEvent.render(): String = buildString {
    append("surface=")
    append(surface.orUnknown())
    append(" query=")
    append(rawQuery.quote())
    append(" planId=")
    append(planId)
    append(" ranking=")
    append(rankingMode)
    append(" candidates(initial=")
    append(initialCandidateCount)
    append(", facet=")
    append(afterFacetCount)
    append(", boolean=")
    append(afterBooleanCount)
    append(", hot=")
    append(afterHotCount)
    append(", cold=")
    append(afterColdCount)
    append(", negative=")
    append(afterNegativeCount)
    append(", final=")
    append(finalResultCount)
    append(')')
    append(" durationMs=")
    append(durationMs)
}

internal fun ItemTraceEvent.render(): String = buildString {
    append("surface=")
    append(surface.orUnknown())
    append(" query=")
    append(rawQuery.quote())
    append(" planId=")
    append(planId)
    append(" item=")
    append(itemId)
    append(" source=")
    append(sourceId)
    append(" type=")
    append(type)
    accountId?.let {
        append(" account=")
        append(it)
    }
    folderId?.let {
        append(" folder=")
        append(it)
    }
    append(" disposition=")
    append(disposition.label)
    append(" clauses=[")
    append(
        clauses.joinToString(separator = " | ") { clause ->
            buildString {
                append(clause.kind)
                append('{')
                append("clause=")
                append(clause.clause.quote())
                append(", stage=")
                append(clause.stage)
                append(", matched=")
                append(clause.matched)
                clause.matchedField?.let {
                    append(", field=")
                    append(it)
                }
                append(", matchedTerms=")
                append(clause.matchedTermCount)
                append(", phrase=")
                append(clause.phraseMatched)
                append(", score=")
                append(clause.scoreContribution.formatScore())
                clause.fieldPresence?.let {
                    append(", fieldPresent=")
                    append(it)
                }
                clause.fieldTokenCount?.let {
                    append(", fieldTokens=")
                    append(it)
                }
                append('}')
            }
        },
    )
    append(']')
}

internal fun SurfaceTraceEvent.render(): String = buildString {
    append("surface=")
    append(surface)
    append(" query=")
    append(rawQuery.quote())
    rawItemCount?.let {
        append(" rawItems=")
        append(it)
    }
    routeFilteredCount?.let {
        append(" routeFiltered=")
        append(it)
    }
    filterFilteredCount?.let {
        append(" filterFiltered=")
        append(it)
    }
    preferredCount?.let {
        append(" preferred=")
        append(it)
    }
    append(" sort=")
    append(activeSort)
    append(" ranking=")
    append(rankingMode)
    append(" finalResults=")
    append(finalResultCount)
}

private fun String.quote(): String = buildString {
    append('"')
    this@quote.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}

private fun Double.formatScore(): String {
    val rounded = (this * 1000.0).roundToInt() / 1000.0
    return rounded.toString()
}

private fun String?.orUnknown(): String = this ?: "unknown"
