package com.artemchep.keyguard.feature.home.vault.search.engine

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

internal data class IndexedFieldValue(
    val raw: String,
    val normalized: String,
    val exactNormalized: String,
    val normalizedTerms: List<String>? = null,
    val exactNormalizedTerms: List<String>? = null,
)

internal data class VaultSearchFieldData(
    val values: List<IndexedFieldValue>,
    val termFrequencies: Map<String, Int>,
    val exactTermFrequencies: Map<String, Int>,
    val totalTerms: Int,
)

internal data class SearchFieldStats(
    val averageLength: Double,
    val documentFrequency: Map<String, Int>,
)

internal data class SearchPosting(
    val docId: Int,
    val termFrequency: Int,
)

internal data class VaultSearchDocument(
    val docId: Int,
    val sourceId: String,
    val source: DSecret,
    val hotFields: Map<VaultTextField, VaultSearchFieldData>,
    val coldFields: Map<VaultTextField, VaultSearchFieldData>,
)

internal data class DocumentBuildResult(
    val document: VaultSearchDocument,
    val hotPostings: Map<VaultTextField, Map<String, Int>>,
    val fieldDocFrequencies: Map<VaultTextField, Set<String>>,
    val fieldLengths: Map<VaultTextField, Int>,
)

internal data class CachedDocumentState(
    val fingerprint: Int,
    val buildResult: DocumentBuildResult,
)

internal data class CachedCorpusState(
    val nextDocId: Int = 0,
    val documentsBySourceId: Map<String, CachedDocumentState> = emptyMap(),
)

internal data class ClauseMatch(
    val score: Double,
    val exactMatchCount: Int = 0,
    val titleTerms: Set<String> = emptySet(),
    val context: MatchContext? = null,
    val trace: ClauseProbe? = null,
)

internal data class ClauseProbe(
    val matched: Boolean,
    val matchedField: VaultTextField? = null,
    val matchedTermCount: Int = 0,
    val exactMatchCount: Int = 0,
    val phraseMatched: Boolean = false,
    val score: Double = 0.0,
    val fieldPresence: Boolean? = null,
    val fieldTokenCount: Int? = null,
    val titleTerms: Set<String> = emptySet(),
    val context: MatchContext? = null,
)

internal data class MatchContext(
    val field: VaultTextField,
    val snippet: String,
    val score: Double,
)

internal data class EvaluatedResult(
    val docId: Int,
    val item: VaultItem2.Item,
    val score: Double,
    val exactMatchCount: Int,
    val order: Int,
    val titleTerms: Set<String>,
    val context: MatchContext?,
    val negativeMatched: Boolean,
)
