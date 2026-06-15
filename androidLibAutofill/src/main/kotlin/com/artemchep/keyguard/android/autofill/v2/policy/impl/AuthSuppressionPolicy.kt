package com.artemchep.keyguard.android.autofill.v2.policy.impl

import android.view.autofill.AutofillId
import com.artemchep.keyguard.android.autofill.v2.model.FieldCluster
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.FormIntent
import com.artemchep.keyguard.android.autofill.v2.model.FormProposal
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType
import com.artemchep.keyguard.android.autofill.v2.policy.ParserPolicy
import com.artemchep.keyguard.android.autofill.v2.policy.PolicyResult

/**
 * Policy that suppresses authentication-related field types (username,
 * password, email, phone) when the form's intent is clearly non-auth
 * (e.g. checkout, search, comment, contact).
 *
 * In checkout, comment, and contact contexts the suppression is relaxed —
 * email fields are kept because they are commonly used for order
 * confirmation, comment attribution, or contact submission alongside
 * non-auth fields.
 */
class AuthSuppressionPolicy : ParserPolicy {
    override val id: String = "auth-suppression"

    override fun apply(
        cluster: FieldCluster,
        fieldProposals: Map<AutofillId, List<FieldProposal>>,
        formProposals: List<FormProposal>,
    ): PolicyResult {
        val suppressAllAuth =
            formProposals.any {
                it.formIntent in NON_AUTH_FORM_INTENTS && it.confidence >= 0.85f
            }
        if (!suppressAllAuth) {
            return PolicyResult()
        }

        val isCheckoutLike =
            formProposals.any {
                it.formIntent in EMAIL_FRIENDLY_INTENTS && it.confidence >= 0.85f
            }
        val suppressedTypes =
            if (isCheckoutLike) {
                // Let resolver decide if the email field is strong enough
                // to survive in checkout context.
                SUPPRESSED_AUTH_TYPES_KEEP_EMAIL
            } else {
                SUPPRESSED_AUTH_TYPES
            }

        return PolicyResult(
            suppressedFieldTypes =
                fieldProposals.keys.associateWith {
                    suppressedTypes
                },
            reasons = listOf("suppressed-auth-in-non-auth-form"),
        )
    }

    private companion object {
        private val NON_AUTH_FORM_INTENTS =
            setOf(
                FormIntent.IGNORE,
                FormIntent.CHECKOUT,
                FormIntent.SEARCH,
                FormIntent.COMMENT,
                FormIntent.CONTACT,
            )

        private val EMAIL_FRIENDLY_INTENTS =
            setOf(
                FormIntent.CHECKOUT,
                FormIntent.COMMENT,
                FormIntent.CONTACT,
            )

        private val SUPPRESSED_AUTH_TYPES =
            setOf(
                SemanticType.USERNAME,
                SemanticType.EMAIL_ADDRESS,
                SemanticType.PHONE_NUMBER,
                SemanticType.PASSWORD,
                SemanticType.NEW_USERNAME,
                SemanticType.NEW_PASSWORD,
            )

        private val SUPPRESSED_AUTH_TYPES_KEEP_EMAIL =
            setOf(
                SemanticType.USERNAME,
                SemanticType.PHONE_NUMBER,
                SemanticType.PASSWORD,
                SemanticType.NEW_USERNAME,
                SemanticType.NEW_PASSWORD,
            )
    }
}
