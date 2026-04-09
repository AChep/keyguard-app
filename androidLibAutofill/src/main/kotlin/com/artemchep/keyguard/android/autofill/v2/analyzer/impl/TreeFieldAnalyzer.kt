package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import com.artemchep.keyguard.android.autofill.v2.analyzer.FieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType

/**
 * Field analyzer backed by the corpus-trained CART decision tree.
 *
 * Extracts a 42-feature vector via [FieldFeatureExtractor], runs it through
 * [GeneratedFieldTree.predict], and converts the probability distribution
 * into [FieldProposal]s that participate in Bayesian log-sum fusion.
 */
class TreeFieldAnalyzer : FieldAnalyzer {
    override val id: String = "tree"

    override fun analyze(
        field: FieldNode,
        context: AnalysisContext,
    ): List<FieldProposal> {
        val features = FieldFeatureExtractor.extract(field, context)
        val distribution = GeneratedFieldTree.predict(features)

        return distribution.mapNotNull { (label, probability) ->
            if (probability < MIN_PROBABILITY) return@mapNotNull null
            val semanticType = LABEL_TO_TYPE[label] ?: return@mapNotNull null
            FieldProposal(
                semanticType = semanticType,
                analyzerId = id,
                confidence = probability.toFloat(),
                reason = "tree:$label:p=${String.format("%.4f", probability)}",
            )
        }
    }

    private companion object {
        /**
         * Probabilities below this threshold are discarded. This must be high
         * enough to prevent the tree's low-confidence "noise" predictions from
         * being penalized heavily in Bayesian log-sum fusion (since
         * ln(0.05) ≈ -3.0 would catastrophically tank the score for that type).
         * A value of 0.50 means only the tree's dominant prediction is emitted.
         */
        const val MIN_PROBABILITY = 0.50

        /** Maps tree output labels to [SemanticType] values. NOT_APPLICABLE is excluded. */
        val LABEL_TO_TYPE: Map<String, SemanticType> =
            mapOf(
                "EMAIL_ADDRESS" to SemanticType.EMAIL_ADDRESS,
                "PASSWORD" to SemanticType.PASSWORD,
                "USERNAME" to SemanticType.USERNAME,
                "PHONE_NUMBER" to SemanticType.PHONE_NUMBER,
                "OTP" to SemanticType.OTP,
                "PERSON_NAME" to SemanticType.PERSON_NAME,
                "GIVEN_NAME" to SemanticType.GIVEN_NAME,
                "FAMILY_NAME" to SemanticType.FAMILY_NAME,
                "SEARCH" to SemanticType.SEARCH,
                "COMMENT" to SemanticType.COMMENT,
                // NOT_APPLICABLE intentionally omitted — the tree's "not a credential"
                // signal is expressed by the absence of credential-type proposals.
            )
    }
}
