package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import com.artemchep.keyguard.android.autofill.v2.analyzer.FormAnalyzer
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldCluster
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FormIntent
import com.artemchep.keyguard.android.autofill.v2.model.FormProposal
import com.artemchep.keyguard.android.autofill.v2.util.KeywordMatcher
import com.artemchep.keyguard.android.autofill.v2.util.KeywordTag
import com.artemchep.keyguard.android.autofill.v2.util.containsAny
import com.artemchep.keyguard.android.autofill.v2.util.fieldBlob
import com.artemchep.keyguard.android.autofill.v2.util.has
import java.util.Locale

/**
 * Determines form intent by matching the cluster's field-type sequence
 * against canonical form templates using Levenshtein edit distance.
 *
 * Button-text keyword signals act as secondary boosts/penalties.
 *
 * Uses [KeywordMatcher] (Aho-Corasick automaton) to scan each blob once
 * instead of making multiple separate substring scans.
 */
class TemplateFormAnalyzer : FormAnalyzer {
    override val id: String = "template-form"

    /**
     * Abstract slot representing a field role in a template.
     * Multiple [com.artemchep.keyguard.android.autofill.v2.model.SemanticType]s can map to the same slot.
     */
    enum class Slot {
        IDENTIFIER,
        PASSWORD,
        NAME,
        OTP,
        SEARCH,
        COMMENT,
        ADDRESS,
        PAYMENT,
        OTHER,
    }

    data class FormTemplate(
        val intent: FormIntent,
        val slots: List<Slot>,
        val baseConfidence: Float = 0.85f,
    )

    override fun analyze(
        cluster: FieldCluster,
        context: AnalysisContext,
    ): List<FormProposal> {
        val fields = cluster.fieldIds.mapNotNull(context.fieldsById::get)
        if (fields.isEmpty()) return listOf(unknownProposal())

        // Map each field to a slot using fast heuristic classification.
        val fieldSlots = fields.map { classifySlot(it, context) }

        // Score each template against the field slot sequence.
        val proposals = mutableListOf<FormProposal>()
        for (template in TEMPLATES) {
            val distance = levenshtein(fieldSlots, template.slots)
            val maxLen = maxOf(fieldSlots.size, template.slots.size)
            if (maxLen == 0) continue
            val normalizedDistance = distance.toFloat() / maxLen.toFloat()
            val matchConfidence = (1.0f - normalizedDistance) * template.baseConfidence
            if (matchConfidence > 0.1f) {
                proposals +=
                    FormProposal(
                        formIntent = template.intent,
                        analyzerId = id,
                        confidence = matchConfidence,
                        reason = "template:${template.intent.name}:dist=$distance/$maxLen",
                    )
            }
        }

        // Apply form-action URL boosts.
        val formAction = context.structure.formActions[cluster.id]
        proposals += formActionBoosts(formAction)

        // Apply button-text boosts.
        val buttonBlob = buildButtonBlob(cluster, fields)
        proposals += buttonBoosts(buttonBlob)

        // Always emit a low-confidence UNKNOWN fallback.
        if (proposals.none { it.formIntent != FormIntent.UNKNOWN }) {
            proposals += unknownProposal()
        }

        // Deduplicate by intent, keeping highest confidence per intent.
        return proposals
            .groupBy { it.formIntent }
            .map { (_, group) -> group.maxByOrNull { it.confidence }!! }
    }

    private fun classifySlot(
        field: FieldNode,
        context: AnalysisContext,
    ): Slot {
        val htmlType = field.htmlType ?: ""
        // Single AC pass over the field blob (cached in AnalysisContext).
        val m =
            context.fieldBlobMatches[field.id]
                ?: KeywordMatcher.match(context.fieldBlobs[field.id] ?: fieldBlob(field))

        return when {
            htmlType == "password" -> Slot.PASSWORD

            htmlType == "search" -> Slot.SEARCH

            m has KeywordTag.PASSWORD -> Slot.PASSWORD

            m has KeywordTag.SEARCH -> Slot.SEARCH

            m has KeywordTag.COMMENT -> Slot.COMMENT

            htmlType == "email" || (m has KeywordTag.EMAIL) -> Slot.IDENTIFIER

            htmlType == "tel" ||
                    (m has KeywordTag.PHONE)
                -> Slot.IDENTIFIER

            m has KeywordTag.USERNAME -> Slot.IDENTIFIER

            m has KeywordTag.OTP -> Slot.OTP

            m has KeywordTag.NAME -> Slot.NAME

            m has KeywordTag.ADDRESS -> Slot.ADDRESS

            (m has KeywordTag.CREDIT_CARD_NUMBER) ||
                    (m has KeywordTag.CARD_SECURITY_CODE) ||
                    (m has KeywordTag.CARD_EXPIRY) -> Slot.PAYMENT

            else -> Slot.OTHER
        }
    }

    private fun buildButtonBlob(
        cluster: FieldCluster,
        fields: List<FieldNode>,
    ): String =
        buildString {
            cluster.buttons.forEach {
                append(it.label.orEmpty())
                append(' ')
                append(it.name.orEmpty())
                append(' ')
            }
            append(cluster.label.orEmpty())
            append(' ')
            append(cluster.surroundingText.orEmpty())
        }.lowercase(Locale.ENGLISH)

    /**
     * Emits form-intent proposals based on keywords found in the form's
     * action URL. These are high-signal because the developer explicitly
     * chose the endpoint name.
     */
    private fun formActionBoosts(formAction: String?): List<FormProposal> {
        if (formAction.isNullOrBlank()) return emptyList()
        val blob = formAction.lowercase(Locale.ENGLISH)
        val fm = KeywordMatcher.match(blob)
        val boosts = mutableListOf<FormProposal>()
        if ((fm has KeywordTag.LOGIN_BUTTON) ||
            containsAny(blob, "authenticate", "auth")
        ) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.LOGIN,
                    analyzerId = id,
                    confidence = 0.55f,
                    reason = "form-action:login",
                )
        }
        if (fm has KeywordTag.SIGNUP_BUTTON) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.SIGN_UP,
                    analyzerId = id,
                    confidence = 0.55f,
                    reason = "form-action:signup",
                )
        }
        if (fm has KeywordTag.SEARCH) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.SEARCH,
                    analyzerId = id,
                    confidence = 0.55f,
                    reason = "form-action:search",
                )
        }
        if (fm has KeywordTag.RESET) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.PASSWORD_RESET,
                    analyzerId = id,
                    confidence = 0.50f,
                    reason = "form-action:reset",
                )
        }
        if (fm has KeywordTag.COMMENT) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.COMMENT,
                    analyzerId = id,
                    confidence = 0.45f,
                    reason = "form-action:comment",
                )
        }
        if (containsAny(blob, "contact", "newsletter", "subscribe")) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.CONTACT,
                    analyzerId = id,
                    confidence = 0.40f,
                    reason = "form-action:contact",
                )
        }
        return boosts
    }

    private fun buttonBoosts(buttonBlob: String): List<FormProposal> {
        val bm = KeywordMatcher.match(buttonBlob)
        val boosts = mutableListOf<FormProposal>()
        if (bm has KeywordTag.LOGIN_BUTTON) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.LOGIN,
                    analyzerId = id,
                    confidence = 0.3f,
                    reason = "button-boost:login",
                )
        }
        if (bm has KeywordTag.SIGNUP_BUTTON) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.SIGN_UP,
                    analyzerId = id,
                    confidence = 0.3f,
                    reason = "button-boost:signup",
                )
        }
        if (bm has KeywordTag.RESET) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.PASSWORD_RESET,
                    analyzerId = id,
                    confidence = 0.3f,
                    reason = "button-boost:reset",
                )
        }
        if (bm has KeywordTag.SEARCH) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.SEARCH,
                    analyzerId = id,
                    confidence = 0.3f,
                    reason = "button-boost:search",
                )
        }
        if (containsAny(buttonBlob, "subscribe", "newsletter")) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.SIGN_UP,
                    analyzerId = id,
                    confidence = 0.25f,
                    reason = "button-boost:subscribe",
                )
        }
        if (containsAny(buttonBlob, "continue", "next", "submit", "proceed", "validate")) {
            boosts +=
                FormProposal(
                    formIntent = FormIntent.LOGIN,
                    analyzerId = id,
                    confidence = 0.15f,
                    reason = "button-boost:submit",
                )
        }
        return boosts
    }

    private fun unknownProposal() =
        FormProposal(
            formIntent = FormIntent.UNKNOWN,
            analyzerId = id,
            confidence = 0.2f,
            reason = "no-template-match",
        )

    companion object {
        /**
         * Canonical form templates. Order within the list doesn't matter;
         * the template with the best (lowest distance) match wins.
         */
        val TEMPLATES =
            listOf(
                // Auth templates
                FormTemplate(FormIntent.LOGIN, listOf(Slot.IDENTIFIER, Slot.PASSWORD), 0.90f),
                FormTemplate(FormIntent.LOGIN, listOf(Slot.IDENTIFIER), 0.70f),
                FormTemplate(FormIntent.LOGIN, listOf(Slot.PASSWORD), 0.60f),
                FormTemplate(FormIntent.SIGN_UP, listOf(Slot.NAME, Slot.IDENTIFIER, Slot.PASSWORD, Slot.PASSWORD), 0.90f),
                FormTemplate(FormIntent.SIGN_UP, listOf(Slot.IDENTIFIER, Slot.PASSWORD, Slot.PASSWORD), 0.88f),
                FormTemplate(FormIntent.SIGN_UP, listOf(Slot.NAME, Slot.IDENTIFIER, Slot.PASSWORD), 0.82f),
                FormTemplate(FormIntent.AUTH_COMBINED, listOf(Slot.NAME, Slot.NAME, Slot.IDENTIFIER, Slot.PASSWORD), 0.85f),
                FormTemplate(FormIntent.PASSWORD_RESET, listOf(Slot.IDENTIFIER), 0.65f),
                FormTemplate(FormIntent.PASSWORD_RESET, listOf(Slot.PASSWORD, Slot.PASSWORD), 0.80f),
                FormTemplate(FormIntent.PASSWORD_RESET, listOf(Slot.PASSWORD, Slot.PASSWORD, Slot.PASSWORD), 0.78f),
                // OTP
                FormTemplate(FormIntent.OTP_CHALLENGE, listOf(Slot.OTP), 0.92f),
                // Non-auth templates
                FormTemplate(FormIntent.SEARCH, listOf(Slot.SEARCH), 0.95f),
                FormTemplate(FormIntent.COMMENT, listOf(Slot.COMMENT), 0.95f),
                FormTemplate(FormIntent.COMMENT, listOf(Slot.NAME, Slot.IDENTIFIER, Slot.COMMENT), 0.88f),
                FormTemplate(FormIntent.CHECKOUT, listOf(Slot.PAYMENT), 0.90f),
                FormTemplate(FormIntent.CHECKOUT, listOf(Slot.PAYMENT, Slot.PAYMENT, Slot.PAYMENT), 0.88f),
                FormTemplate(FormIntent.SHIPPING_ADDRESS, listOf(Slot.NAME, Slot.ADDRESS, Slot.ADDRESS, Slot.ADDRESS), 0.85f),
                FormTemplate(FormIntent.CONTACT, listOf(Slot.NAME, Slot.IDENTIFIER, Slot.NAME), 0.75f),
                FormTemplate(FormIntent.IGNORE, listOf(Slot.OTHER), 0.50f),
            )

        /**
         * Levenshtein edit distance between two sequences.
         * Uses single-row DP to minimize allocations (O(n) space instead of O(m*n)).
         */
        fun <T> levenshtein(
            a: List<T>,
            b: List<T>,
        ): Int {
            val m = a.size
            val n = b.size
            if (m == 0) return n
            if (n == 0) return m
            var prev = IntArray(n + 1) { it }
            var curr = IntArray(n + 1)
            for (i in 1..m) {
                curr[0] = i
                for (j in 1..n) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] =
                        minOf(
                            prev[j] + 1,
                            curr[j - 1] + 1,
                            prev[j - 1] + cost,
                        )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[n]
        }
    }
}
