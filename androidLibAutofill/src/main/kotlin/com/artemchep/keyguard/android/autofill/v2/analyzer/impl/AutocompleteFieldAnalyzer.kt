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
                // HTML: email | Android: emailAddress
                "email", "emailaddress" -> {
                    proposal(SemanticType.EMAIL_ADDRESS, 1.0f, token)
                }

                // HTML: username
                "username" -> {
                    if (looksLikeEmailField(field)) {
                        proposal(SemanticType.EMAIL_ADDRESS, 0.98f, token)
                    } else {
                        proposal(SemanticType.USERNAME, 1.0f, token)
                    }
                }

                // HTML: current-password | Android: password
                "current-password", "password" -> {
                    proposal(SemanticType.PASSWORD, 1.0f, token)
                }

                // HTML: new-password | Android: newPassword
                "new-password", "newpassword" -> {
                    proposal(SemanticType.NEW_PASSWORD, 1.0f, token)
                }

                // HTML: (non-standard) new-username | Android: newUsername
                "new-username", "newusername" -> {
                    proposal(SemanticType.NEW_USERNAME, 1.0f, token)
                }

                // HTML: tel | Android: phone (deprecated), phoneNumber,
                // phoneNational, phoneNumberDevice
                "tel", "phone", "phonenumber", "phonenational",
                "phonenumberdevice",
                    -> {
                    proposal(SemanticType.PHONE_NUMBER, 1.0f, token)
                }

                // HTML: one-time-code | Android: smsOTPCode, emailOTPCode,
                // 2faAppOTPCode
                "one-time-code", "smsotpcode", "emailotpcode",
                "2faappotpcode",
                    -> {
                    proposal(SemanticType.OTP, 1.0f, token)
                }

                // HTML: postal-code | Android: postalCode
                "postal-code", "postalcode" -> {
                    proposal(SemanticType.POSTAL_CODE, 1.0f, token)
                }

                // HTML: address-line1, street-address |
                // Android: postalAddress, streetAddress
                "address-line1", "street-address", "postaladdress",
                "streetaddress",
                    -> {
                    proposal(SemanticType.STREET_ADDRESS, 0.95f, token)
                }

                // HTML: cc-number | Android: creditCardNumber
                "cc-number", "creditcardnumber" -> {
                    proposal(SemanticType.CREDIT_CARD_NUMBER, 1.0f, token)
                }

                // HTML: cc-csc | Android: creditCardSecurityCode
                "cc-csc", "creditcardsecuritycode" -> {
                    proposal(SemanticType.CREDIT_CARD_SECURITY_CODE, 1.0f, token)
                }

                // HTML: cc-exp | Android: creditCardExpirationDate
                "cc-exp", "creditcardexpirationdate" -> {
                    proposal(SemanticType.CREDIT_CARD_EXPIRATION_DATE, 1.0f, token)
                }

                // HTML: cc-exp-month | Android: creditCardExpirationMonth
                "cc-exp-month", "creditcardexpirationmonth" -> {
                    proposal(SemanticType.CREDIT_CARD_EXPIRATION_MONTH, 1.0f, token)
                }

                // HTML: cc-exp-year | Android: creditCardExpirationYear
                "cc-exp-year", "creditcardexpirationyear" -> {
                    proposal(SemanticType.CREDIT_CARD_EXPIRATION_YEAR, 1.0f, token)
                }

                // HTML: name | Android: name (deprecated), personName
                "name", "personname" -> {
                    proposal(SemanticType.PERSON_NAME, 0.85f, token)
                }

                // HTML: given-name | Android: personGivenName
                "given-name", "persongivenname" -> {
                    proposal(SemanticType.GIVEN_NAME, 0.90f, token)
                }

                // HTML: family-name | Android: personFamilyName
                "family-name", "personfamilyname" -> {
                    proposal(SemanticType.FAMILY_NAME, 0.90f, token)
                }

                // HTML: country, country-name | Android: addressCountry
                "country", "country-name", "addresscountry" -> {
                    proposal(SemanticType.COUNTRY, 0.90f, token)
                }

                // Android: addressRegion
                "addressregion" -> {
                    proposal(SemanticType.REGION, 0.90f, token)
                }

                // Android: addressLocality
                "addresslocality" -> {
                    proposal(SemanticType.LOCALITY, 0.90f, token)
                }

                // Android: notApplicable — suppress autofill for this field
                "notapplicable" -> {
                    proposal(SemanticType.UNKNOWN, 1.0f, token)
                }

                else -> {
                    // Android: smsOTPCode1..smsOTPCode8 (per-character OTP)
                    if (token.startsWith("smsotpcode")) {
                        proposal(SemanticType.OTP, 1.0f, token)
                    } else {
                        null
                    }
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
