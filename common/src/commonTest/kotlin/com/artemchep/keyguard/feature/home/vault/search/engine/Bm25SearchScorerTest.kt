package com.artemchep.keyguard.feature.home.vault.search.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class Bm25SearchScorerTest {
    private val scorer = Bm25SearchScorer()

    @Test
    fun `bm25 score increases with term frequency`() {
        val low = scorer.score(
            SearchScoreParams(
                termFrequency = 1,
                documentFrequency = 2,
                documentLength = 10,
                averageDocumentLength = 10.0,
                documentCount = 20,
                fieldBoost = 1.0,
            ),
        )
        val high = scorer.score(
            SearchScoreParams(
                termFrequency = 3,
                documentFrequency = 2,
                documentLength = 10,
                averageDocumentLength = 10.0,
                documentCount = 20,
                fieldBoost = 1.0,
            ),
        )
        assertTrue(high > low)
    }

    @Test
    fun `returns zero for invalid frequencies`() {
        val score = scorer.score(
            SearchScoreParams(
                termFrequency = 0,
                documentFrequency = 0,
                documentLength = 10,
                averageDocumentLength = 10.0,
                documentCount = 20,
                fieldBoost = 1.0,
            ),
        )
        assertTrue(score == 0.0)
    }
}
