package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import com.artemchep.keyguard.android.autofill.v2.analyzer.FieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType

/**
 * Field analyzer that classifies fields based on their `autocomplete` HTML
 * attribute and Android `autofillHints`.
 *
 * Recognized tokens include standard values like `email`, `current-password`,
 * `one-time-code`, `cc-number`, etc. When a field has `autocomplete="username"`
 * but looks like an email field (e.g. `type="email"`), the analyzer promotes
 * it to [SemanticType.EMAIL_ADDRESS] to improve fill accuracy.
 */
class AutocompleteFieldAnalyzer : FieldAnalyzer {
    override val id: String = "autocomplete"

    override fun analyze(
        field: FieldNode,
        context: AnalysisContext,
    ): List<FieldProposal> {
        val tokens =
            buildList {
                addAll(field.autofillHints.map { it.lowercase() })
                field.attributes["autocomplete"]
                    ?.split(WHITESPACE_REGEX)
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.map(String::lowercase)
                    ?.let(::addAll)
            }.distinct()

        return tokens.mapNotNull { token ->
            when (token) {
                "email" -> {
                    proposal(SemanticType.EMAIL_ADDRESS, 1.0f, token)
                }

                "username" -> {
                    if (looksLikeEmailField(field)) {
                        proposal(SemanticType.EMAIL_ADDRESS, 0.98f, token)
                    } else {
                        proposal(SemanticType.USERNAME, 1.0f, token)
                    }
                }

                "current-password" -> {
                    proposal(SemanticType.PASSWORD, 1.0f, token)
                }

                "new-password" -> {
                    proposal(SemanticType.NEW_PASSWORD, 1.0f, token)
                }

                "new-username" -> {
                    proposal(SemanticType.NEW_USERNAME, 1.0f, token)
                }

                "tel", "phone" -> {
                    proposal(SemanticType.PHONE_NUMBER, 1.0f, token)
                }

                "one-time-code" -> {
                    proposal(SemanticType.OTP, 1.0f, token)
                }

                "postal-code" -> {
                    proposal(SemanticType.POSTAL_CODE, 1.0f, token)
                }

                "address-line1", "street-address" -> {
                    proposal(SemanticType.STREET_ADDRESS, 0.95f, token)
                }

                "cc-number" -> {
                    proposal(SemanticType.CREDIT_CARD_NUMBER, 1.0f, token)
                }

                "cc-csc" -> {
                    proposal(SemanticType.CREDIT_CARD_SECURITY_CODE, 1.0f, token)
                }

                "cc-exp" -> {
                    proposal(SemanticType.CREDIT_CARD_EXPIRATION_DATE, 1.0f, token)
                }

                "cc-exp-month" -> {
                    proposal(SemanticType.CREDIT_CARD_EXPIRATION_MONTH, 1.0f, token)
                }

                "cc-exp-year" -> {
                    proposal(SemanticType.CREDIT_CARD_EXPIRATION_YEAR, 1.0f, token)
                }

                "name" -> {
                    proposal(SemanticType.PERSON_NAME, 0.85f, token)
                }

                else -> {
                    null
                }
            }
        }
    }

    private fun looksLikeEmailField(field: FieldNode): Boolean {
        if (field.effectiveType == "email") {
            return true
        }
        val blob =
            buildString {
                append(field.label.orEmpty())
                append(' ')
                append(field.name.orEmpty())
                append(' ')
                append(field.attributes["id"].orEmpty())
                append(' ')
                append(field.attributes["placeholder"].orEmpty())
                append(' ')
                append(field.viewHint.orEmpty())
            }.lowercase()
        return "email" in blob || "e-mail" in blob
    }

    private fun proposal(
        type: SemanticType,
        score: Float,
        token: String,
    ) = FieldProposal(
        semanticType = type,
        analyzerId = id,
        confidence = score,
        reason = "autocomplete:$token",
    )

    private companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
