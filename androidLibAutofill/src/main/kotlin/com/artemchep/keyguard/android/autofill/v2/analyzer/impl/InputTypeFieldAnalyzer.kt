package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import android.text.InputType
import com.artemchep.keyguard.android.autofill.v2.analyzer.FieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType

/**
 * Field analyzer that maps Android [InputType] flags to a [SemanticType].
 *
 * Only fires for **native fields** (where [FieldNode.htmlType] is `null`) to
 * avoid double-counting with [HtmlTypeFieldAnalyzer] on WebView fields.
 *
 * Uses the same [id] as [HtmlTypeFieldAnalyzer] (`"html-type"`) so that
 * proposals share feature-vector slots in the meta-classifier, avoiding
 * the need for retraining.
 */
class InputTypeFieldAnalyzer : FieldAnalyzer {
    /**
     * Intentionally matches [HtmlTypeFieldAnalyzer.id] so proposals map
     * into the same meta-classifier feature columns.
     */
    override val id: String = "html-type"

    override fun analyze(
        field: FieldNode,
        context: AnalysisContext,
    ): List<FieldProposal> {
        // Only fire for native fields — HTML fields are handled by HtmlTypeFieldAnalyzer.
        if (field.htmlType != null) return emptyList()

        val inputType = field.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val proposal =
            when (cls) {
                InputType.TYPE_CLASS_TEXT -> {
                    when (variation) {
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        -> SemanticType.PASSWORD to 1.0f

                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                        -> SemanticType.EMAIL_ADDRESS to 0.95f

                        InputType.TYPE_TEXT_VARIATION_FILTER -> SemanticType.SEARCH to 1.0f

                        else -> null
                    }
                }

                InputType.TYPE_CLASS_PHONE -> {
                    SemanticType.PHONE_NUMBER to 0.95f
                }

                InputType.TYPE_CLASS_NUMBER -> {
                    when (variation) {
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD -> {
                            SemanticType.PASSWORD to 1.0f
                        }

                        else -> {
                            SemanticType.QUANTITY to 0.35f
                        }
                    }
                }

                else -> {
                    null
                }
            } ?: return emptyList()

        return listOf(
            FieldProposal(
                semanticType = proposal.first,
                analyzerId = id,
                confidence = proposal.second,
                reason = "inputType:0x${inputType.toString(16)}",
            ),
        )
    }
}
