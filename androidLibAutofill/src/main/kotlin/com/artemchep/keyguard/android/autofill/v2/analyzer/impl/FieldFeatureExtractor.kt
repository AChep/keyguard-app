package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.util.KeywordMatcher
import com.artemchep.keyguard.android.autofill.v2.util.KeywordTag
import com.artemchep.keyguard.android.autofill.v2.util.autocompleteBlob
import com.artemchep.keyguard.android.autofill.v2.util.has
import com.artemchep.keyguard.android.autofill.v2.util.nameIdBlob
import com.artemchep.keyguard.android.autofill.v2.util.normalizeSignalText
import java.util.Locale

/**
 * Extracts a fixed-size numeric feature vector from a [FieldNode] and its
 * cluster context. Used by both the CART tree trainer and the runtime
 * [TreeFieldAnalyzer].
 *
 * The feature layout is defined by [FEATURE_NAMES]; every index in the
 * returned [DoubleArray] corresponds to the name at the same index.
 *
 * Uses [KeywordMatcher] (Aho-Corasick automaton) to scan each blob once
 * instead of making 8+ separate `containsAny` calls per blob.
 */
object FieldFeatureExtractor {
    /** Human-readable names for each feature slot, in order. */
    val FEATURE_NAMES: List<String> =
        listOf(
            // 0-9: HTML type one-hot
            "htmlType_text",
            "htmlType_email",
            "htmlType_password",
            "htmlType_tel",
            "htmlType_search",
            "htmlType_number",
            "htmlType_checkbox",
            "htmlType_radio",
            "htmlType_select",
            "htmlType_other",
            // 10-12: HTML tag one-hot
            "htmlTag_input",
            "htmlTag_textarea",
            "htmlTag_select",
            // 13-17: autocomplete token flags
            "ac_email",
            "ac_password",
            "ac_tel",
            "ac_username",
            "ac_otp",
            // 18-25: label/placeholder keyword flags
            "label_email",
            "label_password",
            "label_username",
            "label_phone",
            "label_search",
            "label_otp",
            "label_name",
            "label_comment",
            // 26-33: name/id keyword flags
            "name_email",
            "name_password",
            "name_username",
            "name_phone",
            "name_search",
            "name_otp",
            "name_name",
            "name_comment",
            // 34-41: cluster context features
            "fieldPositionNorm",
            "clusterFieldCount",
            "clusterHasPasswordField",
            "clusterHasIdentifierField",
            "buttonHasLogin",
            "buttonHasSignup",
            "buttonHasSearch",
            "buttonHasReset",
            // 42-46: neighbor context features
            "isFirstField",
            "isLastField",
            "precedingIsPassword",
            "precedingIsIdentifier",
            "followingIsPassword",
            // 47-49: form action features
            "formActionHasLogin",
            "formActionHasSignup",
            "formActionHasSearch",
            // 50-51: additional text signal features
            "nameHasDigitPattern",
            "labelHasForgotReset",
            // 52-54: additional autocomplete token features
            "acName",
            "acAddress",
            "acCreditCard",
        )

    val FEATURE_COUNT: Int = FEATURE_NAMES.size

    fun extract(
        field: FieldNode,
        context: AnalysisContext,
    ): DoubleArray {
        val features = DoubleArray(FEATURE_COUNT)
        val htmlType = field.htmlType ?: ""
        val htmlTag = field.htmlTag?.lowercase(Locale.ENGLISH) ?: ""

        // HTML type one-hot (indices 0-9)
        val typeIndex =
            when (htmlType) {
                "text" -> 0
                "email" -> 1
                "password" -> 2
                "tel" -> 3
                "search" -> 4
                "number" -> 5
                "checkbox" -> 6
                "radio" -> 7
                else -> if (htmlTag == "select") 8 else 9
            }
        features[typeIndex] = 1.0

        // HTML tag one-hot (indices 10-12)
        when (htmlTag) {
            "input" -> features[10] = 1.0
            "textarea" -> features[11] = 1.0
            "select" -> features[12] = 1.0
        }

        // Autocomplete token flags (indices 13-17)
        val acBlob = context.autocompleteBlobs[field.id] ?: autocompleteBlob(field)
        if ("email" in acBlob) features[13] = 1.0
        if ("password" in acBlob || "current-password" in acBlob || "new-password" in acBlob) features[14] = 1.0
        if ("tel" in acBlob || "phone" in acBlob) features[15] = 1.0
        if ("username" in acBlob) features[16] = 1.0
        if ("one-time-code" in acBlob || "otp" in acBlob) features[17] = 1.0

        // Label/placeholder keyword flags (indices 18-25)
        // Single AC pass replaces 8+ containsAny calls.
        val labelBlob =
            normalizeSignalText(
                buildString {
                    append(field.label.orEmpty())
                    append(' ')
                    append(field.attributes["placeholder"].orEmpty())
                    append(' ')
                    append(field.viewHint.orEmpty())
                },
            )
        val labelMatch = KeywordMatcher.match(labelBlob)
        if (labelMatch has KeywordTag.EMAIL) features[18] = 1.0
        if (labelMatch has KeywordTag.PASSWORD) features[19] = 1.0
        if (labelMatch has KeywordTag.USERNAME) features[20] = 1.0
        if (labelMatch has KeywordTag.PHONE) features[21] = 1.0
        if (labelMatch has KeywordTag.SEARCH) features[22] = 1.0
        if (labelMatch has KeywordTag.OTP) features[23] = 1.0
        if ((labelMatch has KeywordTag.NAME) && !(labelMatch has KeywordTag.USERNAME)) features[24] = 1.0
        if (labelMatch has KeywordTag.COMMENT) features[25] = 1.0

        // Name/id keyword flags (indices 26-33)
        // Uses cached AC match results from AnalysisContext.
        val nameBlob = context.nameIdBlobs[field.id] ?: nameIdBlob(field)
        val nameMatch = context.nameIdBlobMatches[field.id] ?: KeywordMatcher.match(nameBlob)
        if (nameMatch has KeywordTag.EMAIL) features[26] = 1.0
        if (nameMatch has KeywordTag.PASSWORD) features[27] = 1.0
        if ((nameMatch has KeywordTag.USERNAME) || "login" in nameBlob || "logon" in nameBlob) features[28] = 1.0
        if (nameMatch has KeywordTag.PHONE) features[29] = 1.0
        if (nameMatch has KeywordTag.SEARCH) features[30] = 1.0
        if (nameMatch has KeywordTag.OTP) features[31] = 1.0
        if ((nameMatch has KeywordTag.NAME) && !(nameMatch has KeywordTag.USERNAME)) features[32] = 1.0
        if (nameMatch has KeywordTag.COMMENT) features[33] = 1.0

        // Cluster context features (indices 34-41)
        // Uses pre-computed ClusterFeatureCache to avoid O(N²) recomputation.
        val cluster = field.clusterId?.let { context.clustersById[it] }
        val clusterCache = field.clusterId?.let { context.clusterFeatureCaches[it] }
        val clusterFieldCount = clusterCache?.fieldCount ?: 1
        features[34] = field.index.toDouble() / clusterFieldCount.toDouble()
        features[35] = clusterFieldCount.toDouble()
        features[36] = if (clusterCache?.hasPasswordField == true) 1.0 else 0.0
        features[37] = if (clusterCache?.hasIdentifierField == true) 1.0 else 0.0

        val buttonMatch = clusterCache?.buttonMatch ?: 0L
        features[38] = if (buttonMatch has KeywordTag.LOGIN_BUTTON) 1.0 else 0.0
        features[39] = if (buttonMatch has KeywordTag.SIGNUP_BUTTON) 1.0 else 0.0
        features[40] = if (buttonMatch has KeywordTag.SEARCH) 1.0 else 0.0
        features[41] = if (buttonMatch has KeywordTag.RESET) 1.0 else 0.0

        // Neighbor context features (indices 42-46)
        val clusterFieldIds = cluster?.fieldIds.orEmpty()
        val posInCluster = if (clusterFieldIds.isNotEmpty()) clusterFieldIds.indexOf(field.id) else -1
        features[42] = if (posInCluster == 0) 1.0 else 0.0
        features[43] = if (posInCluster >= 0 && posInCluster == clusterFieldIds.size - 1) 1.0 else 0.0

        val precedingField =
            if (posInCluster > 0) context.fieldsById[clusterFieldIds[posInCluster - 1]] else null
        val followingField =
            if (posInCluster in 0 until clusterFieldIds.size - 1) {
                context.fieldsById[clusterFieldIds[posInCluster + 1]]
            } else {
                null
            }
        features[44] = if (precedingField != null && isPasswordField(precedingField)) 1.0 else 0.0
        features[45] = if (precedingField != null && isIdentifierField(precedingField)) 1.0 else 0.0
        features[46] = if (followingField != null && isPasswordField(followingField)) 1.0 else 0.0

        // Form action features (indices 47-49)
        val formAction = clusterCache?.formActionBlob.orEmpty()
        val formActionMatch = clusterCache?.formActionMatch ?: 0L
        features[47] =
            if ((formActionMatch has KeywordTag.LOGIN_BUTTON) ||
                "auth" in formAction || "authenticate" in formAction
            ) {
                1.0
            } else {
                0.0
            }
        features[48] =
            if (formActionMatch has KeywordTag.SIGNUP_BUTTON) {
                1.0
            } else {
                0.0
            }
        features[49] = if (formActionMatch has KeywordTag.SEARCH) 1.0 else 0.0

        // Additional text signal features (indices 50-51)
        features[50] = if (DIGIT_PATTERN_REGEX.containsMatchIn(nameBlob)) 1.0 else 0.0
        features[51] = if (labelMatch has KeywordTag.RESET) 1.0 else 0.0

        // Additional autocomplete token features (indices 52-54)
        features[52] = if ("name" in acBlob || "given-name" in acBlob || "family-name" in acBlob) 1.0 else 0.0
        features[53] =
            if ("address" in acBlob || "street-address" in acBlob || "postal-code" in acBlob || "country" in acBlob ||
                "region" in acBlob
            ) {
                1.0
            } else {
                0.0
            }
        features[54] = if ("cc-number" in acBlob || "cc-csc" in acBlob || "cc-exp" in acBlob || "cc-name" in acBlob) 1.0 else 0.0

        return features
    }

    /** Matches OTP-style digit patterns in name/id, e.g. "otp1", "code_2", "digit-3", "pin4". */
    private val DIGIT_PATTERN_REGEX =
        Regex("(otp|code|digit|pin|token|verify)[_\\-\\s]?\\d", RegexOption.IGNORE_CASE)

    private fun clusterHasPasswordField(fields: List<FieldNode>): Boolean = fields.any { isPasswordField(it) }

    private fun isPasswordField(field: FieldNode): Boolean = field.effectiveType == "password"

    private fun clusterHasIdentifierField(fields: List<FieldNode>): Boolean = fields.any { isIdentifierField(it) }

    private fun isIdentifierField(field: FieldNode): Boolean {
        val type = field.effectiveType
        if (type == "email" || type == "tel") return true
        val ac = field.attributes["autocomplete"]?.lowercase(Locale.ENGLISH) ?: return false
        return "email" in ac || "username" in ac || "tel" in ac
    }
}
