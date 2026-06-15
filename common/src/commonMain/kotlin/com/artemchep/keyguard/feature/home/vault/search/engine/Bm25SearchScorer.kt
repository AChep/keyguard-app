package com.artemchep.keyguard.feature.home.vault.search.engine

import kotlin.math.ln

class Bm25SearchScorer(
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
) : SearchScorer {
    override fun score(params: SearchScoreParams): Double {
        if (!hasRequiredStatistics(params)) {
            return 0.0
        }

        // BM25 combines corpus rarity with a term-frequency weight that
        // saturates as the term repeats and adjusts for document length.
        val inverseDocumentFrequency = calculateInverseDocumentFrequency(params)
        val termFrequencyWeight = calculateTermFrequencyWeight(
            params = params,
            averageDocumentLength = normalizedAverageDocumentLength(params),
        )
        return params.fieldBoost * inverseDocumentFrequency * termFrequencyWeight
    }

    private fun hasRequiredStatistics(params: SearchScoreParams): Boolean =
        params.termFrequency > 0 &&
                params.documentFrequency > 0 &&
                params.documentCount > 0

    // Keep normalization stable even if the index has no average length yet.
    private fun normalizedAverageDocumentLength(params: SearchScoreParams): Double =
        params.averageDocumentLength
            .takeIf { it > 0.0 }
            ?: 1.0

    private fun calculateInverseDocumentFrequency(
        params: SearchScoreParams,
    ): Double = ln(
        1.0 + (params.documentCount - params.documentFrequency + 0.5) /
                (params.documentFrequency + 0.5),
    )

    private fun calculateDocumentLengthNormalization(
        params: SearchScoreParams,
        averageDocumentLength: Double,
    ): Double = 1.0 - b + b * params.documentLength.toDouble() / averageDocumentLength

    // Repeated matches help, but each additional hit contributes less than the previous one.
    private fun calculateTermFrequencyWeight(
        params: SearchScoreParams,
        averageDocumentLength: Double,
    ): Double {
        val termFrequency = params.termFrequency.toDouble()
        val numerator = termFrequency * (k1 + 1.0)
        val denominator = termFrequency + k1 * calculateDocumentLengthNormalization(
            params = params,
            averageDocumentLength = averageDocumentLength,
        )
        return numerator / denominator
    }
}
