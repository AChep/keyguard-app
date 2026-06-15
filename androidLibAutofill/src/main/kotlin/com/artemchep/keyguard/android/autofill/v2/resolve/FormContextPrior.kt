package com.artemchep.keyguard.android.autofill.v2.resolve

import com.artemchep.keyguard.android.autofill.v2.model.FormIntent
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType

/**
 * Hand-authored prior table encoding how likely a given [SemanticType] is
 * within a particular [FormIntent] context.
 *
 * Values are log-space adjustments applied during Pass 2 of the two-pass
 * resolver. Positive values boost a type; negative values suppress it.
 * A value of 0.0 means the form context has no effect on that type.
 *
 * Convention:
 *  - Strong boost:   +1.0 to +2.0
 *  - Mild boost:     +0.3 to +0.8
 *  - Neutral:         0.0
 *  - Mild suppress:  -0.3 to -0.8
 *  - Strong suppress: -1.0 to -3.0
 *  - Hard suppress:   [SUPPRESS] (field is entirely excluded)
 *
 * Note: NEW_PASSWORD and NEW_USERNAME are folded into PASSWORD / USERNAME
 * by [DefaultStructureResolverV2.foldType] before priors are applied, so
 * they do not appear in this table.
 */
object FormContextPrior {
    /**
     * Sentinel value indicating the type must be hard-suppressed
     * (treated as if it were not proposed at all).
     */
    const val SUPPRESS = -999.0f

    /**
     * Returns the log-space prior adjustment for [semanticType] in the
     * context of [formIntent].
     */
    fun prior(
        semanticType: SemanticType,
        formIntent: FormIntent,
    ): Float = TABLE[formIntent]?.get(semanticType) ?: defaultPrior(semanticType, formIntent)

    /**
     * Returns true if the type should be hard-suppressed for this intent.
     */
    fun isSuppressed(
        semanticType: SemanticType,
        formIntent: FormIntent,
    ): Boolean = prior(semanticType, formIntent) == SUPPRESS

    // ------------------------------------------------------------------ //
    //  Default prior for (type, intent) pairs not explicitly listed.
    // ------------------------------------------------------------------ //

    private fun defaultPrior(
        semanticType: SemanticType,
        formIntent: FormIntent,
    ): Float =
        when {
            // Non-auth types are always suppressed regardless of intent.
            semanticType in NON_AUTH_TYPES -> SUPPRESS

            // Auth-capable types in non-auth intents are suppressed by default.
            formIntent in NON_AUTH_INTENTS -> SUPPRESS

            // Everything else is neutral.
            else -> 0.0f
        }

    // ------------------------------------------------------------------ //
    //  The prior table.
    // ------------------------------------------------------------------ //

    private val TABLE: Map<FormIntent, Map<SemanticType, Float>> =
        mapOf(
            // ---- LOGIN ---- //
            FormIntent.LOGIN to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to +0.8f,
                        SemanticType.USERNAME to +0.8f,
                        SemanticType.PASSWORD to +1.5f,
                        SemanticType.PHONE_NUMBER to +0.3f,
                        SemanticType.OTP to +0.5f,
                        SemanticType.PERSON_NAME to SUPPRESS,
                        SemanticType.GIVEN_NAME to SUPPRESS,
                        SemanticType.FAMILY_NAME to SUPPRESS,
                    ),
            // ---- SIGN_UP ---- //
            FormIntent.SIGN_UP to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to +1.0f,
                        SemanticType.USERNAME to +0.8f,
                        SemanticType.PASSWORD to +1.0f,
                        SemanticType.PHONE_NUMBER to +0.3f,
                        SemanticType.OTP to +0.3f,
                        SemanticType.PERSON_NAME to +0.5f,
                        SemanticType.GIVEN_NAME to +0.5f,
                        SemanticType.FAMILY_NAME to +0.5f,
                    ),
            // ---- AUTH_COMBINED ---- //
            FormIntent.AUTH_COMBINED to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to +0.8f,
                        SemanticType.USERNAME to +0.8f,
                        SemanticType.PASSWORD to +1.0f,
                        SemanticType.PHONE_NUMBER to +0.3f,
                        SemanticType.OTP to +0.3f,
                        SemanticType.PERSON_NAME to +0.5f,
                        SemanticType.GIVEN_NAME to +0.5f,
                        SemanticType.FAMILY_NAME to +0.5f,
                    ),
            // ---- PASSWORD_RESET ---- //
            FormIntent.PASSWORD_RESET to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to +1.0f,
                        SemanticType.USERNAME to +0.5f,
                        SemanticType.PASSWORD to +0.8f,
                        SemanticType.PHONE_NUMBER to +0.3f,
                        SemanticType.OTP to +0.5f,
                        SemanticType.PERSON_NAME to SUPPRESS,
                        SemanticType.GIVEN_NAME to SUPPRESS,
                        SemanticType.FAMILY_NAME to SUPPRESS,
                    ),
            // ---- OTP_CHALLENGE ---- //
            FormIntent.OTP_CHALLENGE to
                    mapOf(
                        SemanticType.OTP to +2.0f,
                        SemanticType.EMAIL_ADDRESS to -0.5f,
                        SemanticType.USERNAME to -0.5f,
                        SemanticType.PASSWORD to SUPPRESS,
                        SemanticType.PHONE_NUMBER to -0.3f,
                        SemanticType.PERSON_NAME to SUPPRESS,
                        SemanticType.GIVEN_NAME to SUPPRESS,
                        SemanticType.FAMILY_NAME to SUPPRESS,
                    ),
            // ---- PROFILE_EDIT ---- //
            FormIntent.PROFILE_EDIT to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to +0.5f,
                        SemanticType.USERNAME to +0.3f,
                        SemanticType.PASSWORD to -0.5f,
                        SemanticType.PHONE_NUMBER to +0.3f,
                        SemanticType.PERSON_NAME to +0.8f,
                        SemanticType.GIVEN_NAME to +0.8f,
                        SemanticType.FAMILY_NAME to +0.8f,
                    ),
            // ---- Non-auth intents: suppress everything by default ---- //
            // except high-confidence email identifiers which may still
            // be useful (e.g., newsletter subscribe with email field).
            FormIntent.SEARCH to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to SUPPRESS,
                        SemanticType.USERNAME to SUPPRESS,
                        SemanticType.PASSWORD to SUPPRESS,
                        SemanticType.PHONE_NUMBER to SUPPRESS,
                        SemanticType.OTP to SUPPRESS,
                    ),
            FormIntent.COMMENT to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to -0.5f,
                        SemanticType.USERNAME to SUPPRESS,
                        SemanticType.PASSWORD to SUPPRESS,
                        SemanticType.PHONE_NUMBER to SUPPRESS,
                        SemanticType.OTP to SUPPRESS,
                    ),
            FormIntent.CONTACT to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to -0.5f,
                        SemanticType.USERNAME to SUPPRESS,
                        SemanticType.PASSWORD to SUPPRESS,
                        SemanticType.PHONE_NUMBER to SUPPRESS,
                        SemanticType.OTP to SUPPRESS,
                    ),
            FormIntent.CHECKOUT to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to -0.5f,
                        SemanticType.USERNAME to SUPPRESS,
                        SemanticType.PASSWORD to SUPPRESS,
                        SemanticType.PHONE_NUMBER to SUPPRESS,
                        SemanticType.OTP to SUPPRESS,
                    ),
            FormIntent.IGNORE to
                    mapOf(
                        SemanticType.EMAIL_ADDRESS to SUPPRESS,
                        SemanticType.USERNAME to SUPPRESS,
                        SemanticType.PASSWORD to SUPPRESS,
                        SemanticType.PHONE_NUMBER to SUPPRESS,
                        SemanticType.OTP to SUPPRESS,
                    ),
        )

    private val NON_AUTH_TYPES =
        setOf(
            SemanticType.SEARCH,
            SemanticType.COMMENT,
            SemanticType.QUANTITY,
            SemanticType.CAPTCHA,
            SemanticType.CONSENT,
            SemanticType.STREET_ADDRESS,
            SemanticType.POSTAL_CODE,
            SemanticType.COUNTRY,
            SemanticType.REGION,
            SemanticType.LOCALITY,
            SemanticType.CREDIT_CARD_NUMBER,
            SemanticType.CREDIT_CARD_SECURITY_CODE,
            SemanticType.CREDIT_CARD_EXPIRATION_DATE,
            SemanticType.CREDIT_CARD_EXPIRATION_MONTH,
            SemanticType.CREDIT_CARD_EXPIRATION_YEAR,
        )

    private val NON_AUTH_INTENTS =
        setOf(
            FormIntent.IGNORE,
            FormIntent.SEARCH,
            FormIntent.COMMENT,
            FormIntent.CONTACT,
            FormIntent.CHECKOUT,
            FormIntent.SHIPPING_ADDRESS,
            FormIntent.BILLING_ADDRESS,
        )
}
