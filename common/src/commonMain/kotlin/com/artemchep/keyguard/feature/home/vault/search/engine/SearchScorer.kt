package com.artemchep.keyguard.feature.home.vault.search.engine

data class SearchScoreParams(
    val termFrequency: Int,
    val documentFrequency: Int,
    val documentLength: Int,
    val averageDocumentLength: Double,
    val documentCount: Int,
    val fieldBoost: Double,
)

interface SearchScorer {
    fun score(params: SearchScoreParams): Double
}
