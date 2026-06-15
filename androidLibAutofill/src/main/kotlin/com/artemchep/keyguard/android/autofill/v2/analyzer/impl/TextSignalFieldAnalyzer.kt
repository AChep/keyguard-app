package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import com.artemchep.keyguard.android.autofill.v2.analyzer.FieldAnalyzer
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType
import com.artemchep.keyguard.android.autofill.v2.util.EXPLICIT_EMAIL_NAME_ID_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.KeywordMatcher
import com.artemchep.keyguard.android.autofill.v2.util.KeywordTag
import com.artemchep.keyguard.android.autofill.v2.util.LOGIN_IDENTIFIER_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.PASSWORD_TOKEN_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.USER_IDENTIFIER_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.autocompleteBlob
import com.artemchep.keyguard.android.autofill.v2.util.containsAny
import com.artemchep.keyguard.android.autofill.v2.util.fieldBlob
import com.artemchep.keyguard.android.autofill.v2.util.has
import com.artemchep.keyguard.android.autofill.v2.util.nameIdBlob

/**
 * Field analyzer that classifies fields by scanning their text signals —
 * labels, names, placeholders, hints, and other attributes — against
 * multilingual keyword lists defined in `FieldSignals.kt`.
 *
 * This is the broadest heuristic analyzer: it covers credentials (password,
 * email, username, OTP), payment (card number, CVV, expiry), address fields,
 * personal names, and non-fillable types (search, comment, captcha, consent).
 * Confidence scores are calibrated so that stronger signals (e.g. an exact
 * keyword match) outweigh weaker ones.
 *
 * Uses [KeywordMatcher] (Aho-Corasick automaton) to scan each blob once
 * instead of making O(keyword_lists) separate substring scans.
 */
class TextSignalFieldAnalyzer : FieldAnalyzer {
    override val id: String = "text-signal"

    override fun analyze(
        field: FieldNode,
        context: AnalysisContext,
    ): List<FieldProposal> {
        val htmlType = field.htmlType
        val blob = context.fieldBlobs[field.id] ?: fieldBlob(field)
        if (blob.isBlank()) return emptyList()
        if (htmlType in NON_TEXT_CONTROLS || field.htmlTag == "select") {
            return when (htmlType) {
                "checkbox", "radio" -> {
                    listOf(
                        proposal(
                            type = SemanticType.CONSENT,
                            score = 0.9f,
                            blob = blob,
                        ),
                    )
                }

                else -> {
                    emptyList()
                }
            }
        }

        // Single Aho-Corasick pass over the field blob — replaces ~20+ containsAny calls.
        val m = context.fieldBlobMatches[field.id] ?: KeywordMatcher.match(blob)
        val emailLike = m has KeywordTag.EMAIL
        val nameOrIdBlob = context.nameIdBlobs[field.id] ?: nameIdBlob(field)
        val acBlob = context.autocompleteBlobs[field.id] ?: autocompleteBlob(field)
        val proposals = mutableListOf<FieldProposal>()
        when {
            m has KeywordTag.SEARCH -> {
                proposals += proposal(SemanticType.SEARCH, 0.98f, blob)
            }

            m has KeywordTag.COMMENT -> {
                proposals += proposal(SemanticType.COMMENT, 0.98f, blob)
            }

            containsAny(blob, "qty", "quantity") -> {
                proposals += proposal(SemanticType.QUANTITY, 0.95f, blob)
            }

            containsAny(blob, "captcha", "verification image") -> {
                proposals += proposal(SemanticType.CAPTCHA, 0.98f, blob)
            }
        }

        when {
            m has KeywordTag.CARD_SECURITY_CODE -> {
                proposals += proposal(SemanticType.CREDIT_CARD_SECURITY_CODE, 0.98f, blob)
            }

            m has KeywordTag.CARD_EXPIRY -> {
                proposals += proposal(SemanticType.CREDIT_CARD_EXPIRATION_DATE, 0.95f, blob)
            }

            (m has KeywordTag.CREDIT_CARD_NUMBER) &&
                    !containsMixedCardIdentifierPhrase(blob, m) -> {
                proposals += proposal(SemanticType.CREDIT_CARD_NUMBER, 0.95f, blob)
            }
        }

        when {
            m has KeywordTag.POSTAL_CODE -> {
                proposals += proposal(SemanticType.POSTAL_CODE, 0.92f, blob)
            }

            (m has KeywordTag.ADDRESS) && !emailLike -> {
                proposals += proposal(SemanticType.STREET_ADDRESS, 0.9f, blob)
            }

            m has KeywordTag.CITY -> {
                proposals += proposal(SemanticType.LOCALITY, 0.85f, blob)
            }

            m has KeywordTag.REGION -> {
                proposals += proposal(SemanticType.REGION, 0.85f, blob)
            }

            m has KeywordTag.COUNTRY -> {
                proposals += proposal(SemanticType.COUNTRY, 0.85f, blob)
            }
        }

        when {
            m has KeywordTag.GIVEN_NAME -> {
                proposals += proposal(SemanticType.GIVEN_NAME, 0.92f, blob)
            }

            m has KeywordTag.FAMILY_NAME -> {
                proposals += proposal(SemanticType.FAMILY_NAME, 0.92f, blob)
            }

            m has KeywordTag.NAME -> {
                proposals += proposal(SemanticType.PERSON_NAME, 0.72f, blob)
            }
        }

        when {
            m has KeywordTag.OTP -> {
                proposals += proposal(SemanticType.OTP, 0.96f, blob)
            }

            containsPasswordToken(blob) && !isEmailResetIdentifier(blob, m, htmlType) -> {
                val confidence =
                    if (isWeakPasswordCueWithStrongEmailIdentity(
                            htmlType = htmlType,
                            nameOrIdBlob = nameOrIdBlob,
                            autocompleteBlob = acBlob,
                        )
                    ) {
                        0.68f
                    } else {
                        0.96f
                    }
                proposals += proposal(SemanticType.PASSWORD, confidence, blob)
            }
        }

        when {
            containsMixedIdentifierPhrase(m) -> {
                proposals += proposal(SemanticType.EMAIL_ADDRESS, 0.95f, blob)
            }

            containsAny(blob, "email or username", "username/email", "user/email", "email or phone") -> {
                proposals += proposal(SemanticType.EMAIL_ADDRESS, 0.93f, blob)
            }

            m has KeywordTag.EMAIL -> {
                proposals += proposal(SemanticType.EMAIL_ADDRESS, 0.9f, blob)
            }

            (m has KeywordTag.PHONE) &&
                    !containsTelFalsePositiveContext(m) -> {
                proposals += proposal(SemanticType.PHONE_NUMBER, 0.92f, blob)
            }

            m has KeywordTag.USERNAME -> {
                proposals += proposal(SemanticType.USERNAME, 0.82f, blob)
            }

            LOGIN_IDENTIFIER_REGEX.containsMatchIn(nameOrIdBlob) -> {
                proposals += proposal(SemanticType.USERNAME, 0.8f, blob)
            }

            containsUserIdentifierToken(nameOrIdBlob) -> {
                proposals += proposal(SemanticType.USERNAME, 0.8f, blob)
            }
        }

        if (htmlType == "checkbox" || htmlType == "radio") {
            proposals += proposal(SemanticType.CONSENT, 0.8f, blob)
        }

        return proposals.distinctBy { it.semanticType to it.reason }
    }

    private fun isEmailResetIdentifier(
        blob: String,
        m: Long,
        htmlType: String?,
    ): Boolean {
        if (htmlType == "email") {
            return true
        }
        val hasEmail = m has KeywordTag.EMAIL
        val hasReset = containsAny(blob, "reset", "recover", "forgot", "lost")
        return hasEmail && hasReset
    }

    private fun containsPasswordToken(blob: String): Boolean = PASSWORD_TOKEN_REGEX.containsMatchIn(blob)

    private fun isWeakPasswordCueWithStrongEmailIdentity(
        htmlType: String?,
        nameOrIdBlob: String,
        autocompleteBlob: String,
    ): Boolean {
        if (htmlType == "password") {
            return false
        }
        if ("current-password" in autocompleteBlob || "new-password" in autocompleteBlob) {
            return false
        }
        val acMatch = KeywordMatcher.match(autocompleteBlob)
        val hasStrongEmailIdentity =
            htmlType == "email" ||
                    EXPLICIT_EMAIL_NAME_ID_REGEX.containsMatchIn(nameOrIdBlob) ||
                    (acMatch has KeywordTag.EMAIL)
        if (!hasStrongEmailIdentity) {
            return false
        }
        return !containsPasswordToken(nameOrIdBlob)
    }

    private fun containsMixedIdentifierPhrase(m: Long): Boolean {
        val hasEmail = m has KeywordTag.EMAIL
        if (!hasEmail) return false
        val hasAlternateIdentifier =
            (m has KeywordTag.USERNAME) ||
                    (m has KeywordTag.PHONE)
        return hasAlternateIdentifier
    }

    private fun containsMixedCardIdentifierPhrase(
        blob: String,
        m: Long,
    ): Boolean {
        val hasCard = m has KeywordTag.CREDIT_CARD_NUMBER
        if (!hasCard) return false
        val hasLoginIdentifier =
            (m has KeywordTag.USERNAME) ||
                    (m has KeywordTag.EMAIL) ||
                    containsAny(
                        blob,
                        "login",
                        "online id",
                        "identifier",
                    )
        if (!hasLoginIdentifier) return false
        return containsAny(blob, " or ", "/", "|")
    }

    private fun containsUserIdentifierToken(nameOrIdBlob: String): Boolean {
        val nm = KeywordMatcher.match(nameOrIdBlob)
        return USER_IDENTIFIER_REGEX.containsMatchIn(nameOrIdBlob) &&
                !(nm has KeywordTag.EMAIL) &&
                !(nm has KeywordTag.PHONE)
    }

    /**
     * Returns true when the field blob's match contains shipping, financial,
     * or utility context keywords that indicate a `type=tel` field is NOT a
     * phone-number credential (e.g. ZIP code, SSN, credit-card CVV).
     */
    private fun containsTelFalsePositiveContext(m: Long): Boolean = m has KeywordTag.TEL_FALSE_POSITIVE

    private fun proposal(
        type: SemanticType,
        score: Float,
        blob: String,
    ) = FieldProposal(
        semanticType = type,
        analyzerId = id,
        confidence = score,
        reason = "text:$blob",
    )

    private companion object {
        private val NON_TEXT_CONTROLS =
            setOf(
                "checkbox",
                "radio",
                "hidden",
                "submit",
                "button",
                "image",
            )
    }
}
