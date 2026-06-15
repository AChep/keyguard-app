package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import com.artemchep.keyguard.android.autofill.v2.analyzer.FieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType

/**
 * Field analyzer that maps the HTML `type` attribute (or tag name) to a
 * [SemanticType].
 *
 * Handles well-known types such as `email`, `password`, `tel`, `search`,
 * and `number`, as well as non-standard password variants (e.g. `password2`).
 * Checkbox and radio inputs are mapped to [SemanticType.CONSENT].
 */
class HtmlTypeFieldAnalyzer : FieldAnalyzer {
    override val id: String = "html-type"

    override fun analyze(
        field: FieldNode,
        context: AnalysisContext,
    ): List<FieldProposal> {
        val type = field.htmlType ?: field.htmlTag?.lowercase()
        val proposal =
            when (type) {
                "email" -> {
                    SemanticType.EMAIL_ADDRESS to 0.95f
                }

                "password" -> {
                    SemanticType.PASSWORD to 1.0f
                }

                "tel" -> {
                    SemanticType.PHONE_NUMBER to 0.95f
                }

                "search" -> {
                    SemanticType.SEARCH to 1.0f
                }

                "number" -> {
                    SemanticType.QUANTITY to 0.35f
                }

                "radio", "checkbox" -> {
                    SemanticType.CONSENT to 0.75f
                }

                else -> {
                    // Fuzzy match for password-like types (e.g. "password2",
                    // "password1") that are typos or non-standard variants.
                    if (type != null && type.startsWith("password")) {
                        SemanticType.PASSWORD to 0.95f
                    } else {
                        null
                    }
                }
            } ?: return emptyList()

        return listOf(
            FieldProposal(
                semanticType = proposal.first,
                analyzerId = id,
                confidence = proposal.second,
                reason = "type:$type",
            ),
        )
    }
}
