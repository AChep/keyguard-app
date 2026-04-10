package com.artemchep.keyguard.android.autofill.v2.model

import android.text.InputType
import android.view.autofill.AutofillId
import com.artemchep.keyguard.android.autofill.v2.util.KeywordMatcher
import com.artemchep.keyguard.android.autofill.v2.util.autocompleteBlob
import com.artemchep.keyguard.android.autofill.v2.util.fieldBlob
import com.artemchep.keyguard.android.autofill.v2.util.nameIdBlob
import java.util.Locale

/**
 * The resolved autofill meaning of a single input field.
 *
 * The resolver assigns exactly one [SemanticType] per field. Non-fillable
 * types (SEARCH, COMMENT, QUANTITY, CAPTCHA, CONSENT) are used to
 * *suppress* autofill rather than provide a fill value.
 */
enum class SemanticType {
    UNKNOWN,
    USERNAME,
    EMAIL_ADDRESS,
    PASSWORD,
    NEW_USERNAME,
    NEW_PASSWORD,
    PHONE_NUMBER,
    OTP,
    PERSON_NAME,
    GIVEN_NAME,
    FAMILY_NAME,
    STREET_ADDRESS,
    POSTAL_CODE,
    COUNTRY,
    REGION,
    LOCALITY,
    CREDIT_CARD_NUMBER,
    CREDIT_CARD_SECURITY_CODE,
    CREDIT_CARD_EXPIRATION_DATE,
    CREDIT_CARD_EXPIRATION_MONTH,
    CREDIT_CARD_EXPIRATION_YEAR,
    SEARCH,
    COMMENT,
    QUANTITY,
    CAPTCHA,
    CONSENT,
}

/**
 * A submit / action button discovered during view-tree extraction.
 *
 * Button labels and attributes are used during clustering (e.g. to detect
 * "Sign In" or "Register" buttons) and by the template form analyzer to
 * infer form intent.
 */
data class ButtonNode(
    val index: Int = 0,
    val label: String? = null,
    val name: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val htmlTag: String? = null,
    val htmlType: String? = null,
    val className: String? = null,
    val attributes: Map<String, String?> = emptyMap(),
    val clusterId: String? = null,
)

/**
 * High-level purpose of a form cluster, determined by the resolver
 * after combining form-analyzer proposals.
 *
 * The form intent influences field-level classification through
 * [FormContextPrior][com.artemchep.keyguard.android.autofill.v2.resolve.FormContextPrior]
 * and policies like [AuthSuppressionPolicy][com.artemchep.keyguard.android.autofill.v2.policy.impl.AuthSuppressionPolicy].
 */
enum class FormIntent {
    UNKNOWN,
    GENERAL,
    LOGIN,
    SIGN_UP,
    AUTH_COMBINED,
    PASSWORD_RESET,
    OTP_CHALLENGE,
    CHECKOUT,
    SHIPPING_ADDRESS,
    BILLING_ADDRESS,
    PAYMENT_METHOD,
    SEARCH,
    COMMENT,
    CONTACT,
    IGNORE,
    PROFILE_EDIT,
    CONTACT_EDIT,
}

/**
 * Coarse category assigned to a [FieldCluster] during the clustering stage.
 *
 * The cluster type is a heuristic hint (based on field/button keywords and
 * form-action URLs) used by the resolver to seed form-intent voting and
 * apply structural priors.
 */
enum class ClusterType {
    UNKNOWN,
    GENERAL,
    AUTH,
    ACCOUNT,
    CONTACT,
    ADDRESS,
    PAYMENT,
    OTP,
    PROFILE,
}

/** Caller-supplied flags that control parser behaviour. */
data class ParseOptions(
    val respectAutofillOff: Boolean = true,
    /**
     * The [AutofillId] of the field that currently holds input focus.
     *
     * When set, the resolver dampens the NONE class probability for this
     * field (and, to a lesser degree, for other fields in the same cluster)
     * so that borderline fields the user explicitly tapped are more likely
     * to receive a non-NONE classification.
     *
     * Additionally, the orchestrator uses the focused field's origin
     * (native vs. WebView, and frame context within a WebView) to apply
     * origin isolation — only fields from the same origin are included
     * in the final result, preventing credential spill across view boundaries.
     */
    val focusedFieldId: AutofillId? = null,
)

/**
 * Normalized representation of a single fillable input field extracted
 * from the [AssistStructure][android.app.assist.AssistStructure] view tree.
 *
 * All text-bearing attributes (label, name, hints, HTML attributes) are
 * preserved so that downstream analyzers can inspect them for classification.
 */
data class FieldNode(
    val id: AutofillId,
    val index: Int = 0,
    val label: String? = null,
    val name: String? = null,
    val viewHint: String? = null,
    val value: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val htmlTag: String? = null,
    val className: String? = null,
    val inputType: Int = 0,
    val autofillType: Int = 0,
    /**
     * The [View.importantForAutofill][android.view.View.getImportantForAutofill]
     * value reported by the platform. When this equals
     * [View.IMPORTANT_FOR_AUTOFILL_NO] the app has explicitly opted the field
     * out of autofill.
     */
    val importantForAutofill: Int = 0,
    val autofillHints: List<String> = emptyList(),
    val attributes: Map<String, String?> = emptyMap(),
    val parentWebViewNodeId: Int? = null,
    val clusterId: String? = null,
    val idPackage: String? = null,
    val idEntry: String? = null,
    /** True when this field originates from a native Android view (no [htmlTag]). */
    val isNative: Boolean = false,
    /**
     * Identifies the frame context within a WebView.
     *
     * Fields in the main frame of a WebView have `null`. Fields inside an
     * `<iframe>` receive a unique ID derived from the traversal path.
     * This is used by origin isolation to prevent credential spill across
     * iframe boundaries within the same WebView.
     */
    val frameContextId: String? = null,
    /**
     * Whether the original [AssistStructure.ViewNode][android.app.assist.AssistStructure.ViewNode]
     * was enabled. Disabled fields are still extracted for save-detection
     * (e.g. a pre-filled identifier on a password-step screen) but may be
     * excluded from fill datasets by the service.
     */
    val isEnabled: Boolean = true,
) {
    /** Pre-computed lowercased HTML type attribute; avoids repeated map lookup + lowercase(). */
    val htmlType: String? = attributes["type"]?.lowercase()

    /**
     * Effective type for classification — prefers [htmlType], falls back to
     * an equivalent string derived from [inputType] flags for native fields.
     */
    val effectiveType: String?
        get() = htmlType ?: inputTypeToEffectiveType(inputType)
}

/**
 * Maps Android [InputType] flags to an HTML-equivalent type string.
 * Returns `null` when no meaningful mapping exists.
 */
private fun inputTypeToEffectiveType(inputType: Int): String? {
    val cls = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (cls) {
        InputType.TYPE_CLASS_TEXT -> {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    -> "password"

                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    -> "email"

                InputType.TYPE_TEXT_VARIATION_URI -> "url"

                InputType.TYPE_TEXT_VARIATION_FILTER -> "search"

                else -> null
            }
        }

        InputType.TYPE_CLASS_PHONE -> {
            "tel"
        }

        InputType.TYPE_CLASS_NUMBER -> {
            when (variation) {
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "password"
                else -> "number"
            }
        }

        else -> {
            null
        }
    }
}

/**
 * A group of related fields (and their associated buttons) that belong to
 * the same logical form.
 *
 * Clusters are built during stage 2 from `<form>` boundaries and cluster IDs.
 * Each cluster carries a heuristic [ClusterType] and optional contextual text
 * used by the resolver.
 */
data class FieldCluster(
    val id: String,
    val type: ClusterType = ClusterType.UNKNOWN,
    val fieldIds: List<AutofillId> = emptyList(),
    val buttons: List<ButtonNode> = emptyList(),
    val label: String? = null,
    val surroundingText: String? = null,
    val metadata: Map<String, String?> = emptyMap(),
)

/**
 * Domain and scheme metadata for a single WebView, keyed by
 * [AssistStructure.ViewNode.getId][android.app.assist.AssistStructure.ViewNode.getId].
 */
data class WebViewInfo(
    val webDomain: String? = null,
    val webScheme: String? = null,
)

/**
 * Flat, normalized view of the entire [AssistStructure][android.app.assist.AssistStructure].
 *
 * After extraction, this contains fields, buttons, web-domain info, and
 * form actions. After clustering, the [clusters] list is populated with
 * [FieldCluster]s that group related fields together.
 */
data class NormalizedStructureV2(
    val applicationId: String? = null,
    val webScheme: String? = null,
    val webDomain: String? = null,
    val webView: Boolean = false,
    val fields: List<FieldNode> = emptyList(),
    val buttons: List<ButtonNode> = emptyList(),
    val clusters: List<FieldCluster> = emptyList(),
    /** Form action URLs keyed by clusterId, populated when <form> boundaries are detected. */
    val formActions: Map<String, String?> = emptyMap(),
    /**
     * Per-WebView domain/scheme metadata, keyed by the WebView's
     * [ViewNode id][android.app.assist.AssistStructure.ViewNode.getId].
     *
     * Used by origin isolation to select the correct web metadata when
     * the focused field belongs to a specific WebView.
     */
    val webViewMetadata: Map<Int, WebViewInfo> = emptyMap(),
)

/**
 * Pre-computed per-cluster data used by [com.artemchep.keyguard.android.autofill.v2.analyzer.impl.FieldFeatureExtractor]
 * to avoid O(N²) recomputation when extracting features for every field in a cluster.
 */
data class ClusterFeatureCache(
    val fields: List<FieldNode>,
    val fieldCount: Int,
    val hasPasswordField: Boolean,
    val hasIdentifierField: Boolean,
    val buttonBlob: String,
)

/**
 * Shared context object passed to all analyzers during resolution.
 *
 * Pre-computes and caches per-field text blobs (field blob, name/id blob,
 * autocomplete blob) so that multiple analyzers can reuse them without
 * redundant string concatenation.
 */
data class AnalysisContext(
    val structure: NormalizedStructureV2,
    val options: ParseOptions = ParseOptions(),
) {
    val fieldsById: Map<AutofillId, FieldNode> = structure.fields.associateBy { it.id }

    val clustersById: Map<String, FieldCluster> = structure.clusters.associateBy { it.id }

    // ── Cached per-field blobs (computed once, reused across all analyzers) ── //

    /** Cached [fieldBlob] results keyed by field id. */
    val fieldBlobs: Map<AutofillId, String> by lazy {
        structure.fields.associate { it.id to fieldBlob(it) }
    }

    /** Cached [nameIdBlob] results keyed by field id. */
    val nameIdBlobs: Map<AutofillId, String> by lazy {
        structure.fields.associate { it.id to nameIdBlob(it) }
    }

    /** Cached [autocompleteBlob] results keyed by field id. */
    val autocompleteBlobs: Map<AutofillId, String> by lazy {
        structure.fields.associate { it.id to autocompleteBlob(it) }
    }

    // ── Cached Aho-Corasick keyword match results (computed once per blob type) ── //

    /**
     * Cached [KeywordMatcher.match] results for each field's [fieldBlob].
     * Each value is a [Long] bitmask of [com.artemchep.keyguard.android.autofill.v2.util.KeywordTag]s.
     */
    val fieldBlobMatches: Map<AutofillId, Long> by lazy {
        structure.fields.associate { it.id to KeywordMatcher.match(fieldBlobs[it.id]!!) }
    }

    /**
     * Cached [KeywordMatcher.match] results for each field's [nameIdBlob].
     * Each value is a [Long] bitmask of [com.artemchep.keyguard.android.autofill.v2.util.KeywordTag]s.
     */
    val nameIdBlobMatches: Map<AutofillId, Long> by lazy {
        structure.fields.associate { it.id to KeywordMatcher.match(nameIdBlobs[it.id]!!) }
    }

    // ── Cached per-cluster feature data (computed once, reused across all fields in cluster) ── //

    /** Cached per-cluster derived data for [FieldFeatureExtractor], avoiding O(N²) recomputation. */
    val clusterFeatureCaches: Map<String, ClusterFeatureCache> by lazy {
        structure.clusters.associate { cluster ->
            val fields = cluster.fieldIds.mapNotNull { fieldsById[it] }
            val hasPassword = fields.any { it.effectiveType == "password" }
            val hasIdentifier =
                fields.any { f ->
                    val type = f.effectiveType
                    if (type == "email" || type == "tel") return@any true
                    val ac = f.attributes["autocomplete"]?.lowercase(Locale.ENGLISH) ?: return@any false
                    "email" in ac || "username" in ac || "tel" in ac
                }
            val buttonBlob =
                cluster.buttons
                    .joinToString(
                        " ",
                    ) { "${it.label.orEmpty()} ${it.name.orEmpty()} ${it.text.orEmpty()} ${it.contentDescription.orEmpty()}" }
                    .lowercase(Locale.ENGLISH)
            cluster.id to
                    ClusterFeatureCache(
                        fields = fields,
                        fieldCount = fields.size.coerceAtLeast(1),
                        hasPasswordField = hasPassword,
                        hasIdentifierField = hasIdentifier,
                        buttonBlob = buttonBlob,
                    )
        }
    }
}

/** Common contract for both field-level and form-level analyzer proposals. */
interface AnalyzerProposal {
    val analyzerId: String
    val confidence: Float
    val reason: String?
}

/**
 * A single analyzer's vote for what [SemanticType] a field should have,
 * together with a confidence score and optional debug reason.
 */
data class FieldProposal(
    val semanticType: SemanticType,
    override val analyzerId: String,
    override val confidence: Float,
    override val reason: String? = null,
    val details: Map<String, String?> = emptyMap(),
) : AnalyzerProposal

/**
 * A single analyzer's vote for what [FormIntent] a cluster should have,
 * together with a confidence score and optional debug reason.
 */
data class FormProposal(
    val clusterId: String? = null,
    val formIntent: FormIntent,
    override val analyzerId: String,
    override val confidence: Float,
    override val reason: String? = null,
    val details: Map<String, String?> = emptyMap(),
) : AnalyzerProposal

/** Final output of the v2 parser: the resolved field types and form intents. */
data class ParseResultV2(
    val structure: NormalizedStructureV2,
    val fieldTypes: Map<AutofillId, SemanticType> = emptyMap(),
    val formIntents: Map<String, FormIntent> = emptyMap(),
)

/**
 * Extended parse result that includes all intermediate analyzer proposals
 * and debug notes alongside the final [ParseResultV2], useful for
 * diagnostics and test assertions.
 */
data class ParseDebugResultV2(
    val result: ParseResultV2,
    val fieldProposals: Map<AutofillId, List<FieldProposal>> = emptyMap(),
    val formProposals: Map<String, List<FormProposal>> = emptyMap(),
    val notes: List<String> = emptyList(),
)
