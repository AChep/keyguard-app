package com.artemchep.keyguard.android.autofill.v2.resolve

import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.FormIntent
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType
import com.artemchep.keyguard.android.autofill.v2.util.NAME_ID_LOGIN_REGEX
import com.artemchep.keyguard.android.autofill.v2.util.autocompleteBlob
import com.artemchep.keyguard.android.autofill.v2.util.nameIdBlob

/**
 * Extracts the meta-classifier feature vector for a single field.
 *
 * Feature layout (52 features total):
 *   - 0-31:  per-analyzer per-type max confidence (4 analyzers × 8 types)
 *   - 32-39: number of distinct analyzers proposing each type (8)
 *   - 40-45: form intent one-hot (6)
 *   - 46-48: label signal tier (3)
 *   - 49-51: cluster context (3)
 */
object MetaFeatureExtractor {
    const val NUM_FEATURES = 52

    // ── Analyzer IDs (ordering matters for feature vector layout) ── //
    private val ANALYZER_IDS = listOf("autocomplete", "html-type", "text-signal", "tree")

    // ── Semantic types we track per-analyzer ────────────────────── //
    private val TRACKED_TYPES =
        listOf(
            SemanticType.EMAIL_ADDRESS,
            SemanticType.PASSWORD,
            SemanticType.USERNAME,
            SemanticType.PHONE_NUMBER,
            SemanticType.OTP,
            SemanticType.PERSON_NAME,
            SemanticType.SEARCH,
            SemanticType.COMMENT,
        )

    private val NUM_ANALYZERS = ANALYZER_IDS.size // 4
    private val NUM_TYPES = TRACKED_TYPES.size // 8

    /** Maps analyzer ID → index (0..3). Unknown analyzers → -1. */
    private val ANALYZER_INDEX: Map<String, Int> =
        ANALYZER_IDS.withIndex().associate { (i, id) -> id to i }

    /** Maps SemanticType → index (0..7). Unmapped types are absent. */
    private val TYPE_INDEX: Map<SemanticType, Int> =
        TRACKED_TYPES.withIndex().associate { (i, t) -> t to i }

    // ── Form intent one-hot slots ─────────────────────────────────── //
    private val FORM_INTENT_SLOTS =
        listOf(
            FormIntent.LOGIN,
            FormIntent.SIGN_UP,
            FormIntent.AUTH_COMBINED,
            FormIntent.PASSWORD_RESET,
            FormIntent.SEARCH,
            FormIntent.UNKNOWN,
        )

    fun extract(
        field: FieldNode,
        proposals: List<FieldProposal>,
        formIntent: FormIntent,
        context: AnalysisContext,
    ): DoubleArray {
        val features = DoubleArray(NUM_FEATURES)

        // ── Single pass through proposals ────────────────────────── //
        // maxConf[analyzerIdx * NUM_TYPES + typeIdx] = max confidence
        val maxConf = DoubleArray(NUM_ANALYZERS * NUM_TYPES)
        // analyzerBitmask[typeIdx] = bitmask of which analyzers proposed this type
        val analyzerBitmask = IntArray(NUM_TYPES)

        for (p in proposals) {
            val typeIdx = TYPE_INDEX[p.semanticType] ?: continue
            val analyzerIdx = ANALYZER_INDEX[p.analyzerId] ?: continue
            val conf = p.confidence.toDouble()

            // Update per-analyzer per-type max confidence
            val slot = analyzerIdx * NUM_TYPES + typeIdx
            if (conf > maxConf[slot]) maxConf[slot] = conf

            // Mark this analyzer as proposing this type
            analyzerBitmask[typeIdx] = analyzerBitmask[typeIdx] or (1 shl analyzerIdx)
        }

        // Features 0-31: per-analyzer per-type max confidence
        System.arraycopy(maxConf, 0, features, 0, NUM_ANALYZERS * NUM_TYPES)

        // Features 32-39: number of distinct analyzers proposing each type
        var idx = NUM_ANALYZERS * NUM_TYPES
        for (typeIdx in 0 until NUM_TYPES) {
            features[idx++] = Integer.bitCount(analyzerBitmask[typeIdx]).toDouble()
        }

        // Features 40-45: form intent one-hot
        for (intent in FORM_INTENT_SLOTS) {
            features[idx++] = if (formIntent == intent) 1.0 else 0.0
        }

        // Features 46-48: label signal tier
        features[idx++] = if (hasVisibleLabel(field)) 1.0 else 0.0
        features[idx++] = if (hasStructuralSignal(field, context)) 1.0 else 0.0
        features[idx++] = if (hasNameIdLoginSignal(field, context)) 1.0 else 0.0

        // Features 49-51: cluster context
        val cluster = field.clusterId?.let { context.clustersById[it] }
        val clusterFields = cluster?.fieldIds?.mapNotNull { context.fieldsById[it] }.orEmpty()
        val clusterFieldCount = clusterFields.size.coerceAtLeast(1)
        features[idx++] = (clusterFieldCount.toDouble() / 10.0).coerceAtMost(1.0)
        features[idx++] = if (clusterFields.any { it.effectiveType == "password" }) 1.0 else 0.0
        features[idx] =
            if (clusterFields.any { f ->
                    f.effectiveType == "email" || f.effectiveType == "tel"
                }
            ) {
                1.0
            } else {
                0.0
            }

        return features
    }

    private fun hasVisibleLabel(field: FieldNode): Boolean =
        !field.label.isNullOrBlank() ||
                !field.attributes["placeholder"].isNullOrBlank() ||
                !field.viewHint.isNullOrBlank() ||
                !field.contentDescription.isNullOrBlank()

    private fun hasStructuralSignal(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        val effectiveType = field.effectiveType
        if (effectiveType == "password" || effectiveType == "email" || effectiveType == "tel") return true
        val acBlob = context.autocompleteBlobs[field.id] ?: autocompleteBlob(field)
        return "password" in acBlob || "email" in acBlob || "username" in acBlob || "tel" in acBlob
    }

    private fun hasNameIdLoginSignal(
        field: FieldNode,
        context: AnalysisContext,
    ): Boolean {
        val blob = context.nameIdBlobs[field.id] ?: nameIdBlob(field)
        return NAME_ID_LOGIN_REGEX.containsMatchIn(blob)
    }
}
