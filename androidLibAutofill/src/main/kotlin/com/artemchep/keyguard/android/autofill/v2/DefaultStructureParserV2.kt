package com.artemchep.keyguard.android.autofill.v2

import android.app.assist.AssistStructure
import android.view.autofill.AutofillId
import com.artemchep.keyguard.android.autofill.v2.api.StructureParserV2
import com.artemchep.keyguard.android.autofill.v2.cluster.DefaultStructureClusterBuilderV2
import com.artemchep.keyguard.android.autofill.v2.cluster.StructureClusterBuilderV2
import com.artemchep.keyguard.android.autofill.v2.extract.DefaultStructureExtractorV2
import com.artemchep.keyguard.android.autofill.v2.extract.StructureExtractorV2
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.ParseDebugResultV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseOptions
import com.artemchep.keyguard.android.autofill.v2.model.ParseResultV2
import com.artemchep.keyguard.android.autofill.v2.resolve.DefaultStructureResolverV2
import com.artemchep.keyguard.android.autofill.v2.resolve.StructureResolverV2

/**
 * Default orchestrator for the three-stage v2 autofill parser pipeline:
 * **extract** (view-tree traversal) → **cluster** (field grouping and cluster-type
 * classification) → **resolve** (Bayesian field-type and form-intent resolution).
 *
 * After resolution, [applyOriginIsolation] enforces a hard security boundary
 * that prevents credential spill between native views, different WebViews,
 * and iframe contexts within a WebView (AutoSpill mitigation).
 *
 * Each stage is pluggable via constructor parameters for testing.
 */
class DefaultStructureParserV2(
    private val extractor: StructureExtractorV2 = DefaultStructureExtractorV2(),
    private val clusterBuilder: StructureClusterBuilderV2 = DefaultStructureClusterBuilderV2(),
    private val resolver: StructureResolverV2 = DefaultStructureResolverV2(),
) : StructureParserV2 {
    override fun parse(
        assistStructure: AssistStructure,
        options: ParseOptions,
    ): ParseResultV2 =
        parseDebug(
            assistStructure = assistStructure,
            options = options,
        ).result

    override fun parseDebug(
        assistStructure: AssistStructure,
        options: ParseOptions,
    ): ParseDebugResultV2 {
        val extracted =
            extractor.extract(
                assistStructure = assistStructure,
                options = options,
            )
        val clustered = clusterBuilder.build(extracted)
        val resolved = resolver.resolve(clustered, options)
        return applyOriginIsolation(resolved, options)
    }

    // ------------------------------------------------------------------ //
    //  Origin isolation (AutoSpill mitigation)
    // ------------------------------------------------------------------ //

    /**
     * Enforces origin isolation on the resolved result to prevent credential
     * spill across view boundaries.
     *
     * The isolation works at three levels:
     * 1. **Native vs. WebView** — native fields are never mixed with WebView fields.
     * 2. **WebView vs. WebView** — fields from different WebViews are isolated.
     * 3. **Frame context** — fields from different `<iframe>`s within the same
     *    WebView are isolated.
     *
     * When [ParseOptions.focusedFieldId] is set, the focused field's origin
     * determines which fields are kept. When it is not set, the parser falls
     * back to V1-compatible behaviour: if any WebView is present, only WebView
     * fields are included (all frames).
     *
     * Structure-level metadata ([NormalizedStructureV2.webDomain],
     * [NormalizedStructureV2.webScheme], [NormalizedStructureV2.webView]) is
     * adjusted to match the surviving fields so that downstream credential
     * matching does not use metadata from an ignored origin.
     */
    private fun applyOriginIsolation(
        debugResult: ParseDebugResultV2,
        options: ParseOptions,
    ): ParseDebugResultV2 {
        val result = debugResult.result
        val structure = result.structure
        val fieldsById = structure.fields.associateBy { it.id }

        // Determine the focused field (if any).
        val focusedField: FieldNode? =
            options.focusedFieldId?.let { fieldsById[it] }

        // Compute the set of field IDs that are allowed through isolation.
        val allowedIds: Set<AutofillId>
        // Compute the adjusted web metadata for the output structure.
        val adjustedWebDomain: String?
        val adjustedWebScheme: String?
        val adjustedWebView: Boolean

        when {
            // ── Focused field is native ─────────────────────────────── //
            focusedField != null && focusedField.isNative -> {
                allowedIds =
                    structure.fields
                        .filter { it.isNative }
                        .map { it.id }
                        .toSet()
                // Strip all web metadata — the credential matcher must not
                // use a nearby WebView's domain to look up credentials.
                adjustedWebDomain = null
                adjustedWebScheme = null
                adjustedWebView = false
            }

            // ── Focused field is in a WebView ───────────────────────── //
            focusedField != null && focusedField.parentWebViewNodeId != null -> {
                val targetWebViewId = focusedField.parentWebViewNodeId
                val targetFrameContext = focusedField.frameContextId
                allowedIds =
                    structure.fields
                        .filter { field ->
                            field.parentWebViewNodeId == targetWebViewId &&
                                    field.frameContextId == targetFrameContext
                        }.map { it.id }
                        .toSet()
                // Use the focused field's WebView metadata.
                val info = structure.webViewMetadata[targetWebViewId]
                adjustedWebDomain = info?.webDomain ?: structure.webDomain
                adjustedWebScheme = info?.webScheme ?: structure.webScheme
                adjustedWebView = true
            }

            // ── No focus, but WebView(s) present — V1 parity ────────── //
            // Drop native fields to prevent AutoSpill. All WebView
            // fields (across all frames) are kept because without a focus
            // signal we cannot narrow further.
            focusedField == null && structure.webView -> {
                allowedIds =
                    structure.fields
                        .filter { it.parentWebViewNodeId != null }
                        .map { it.id }
                        .toSet()
                adjustedWebDomain = structure.webDomain
                adjustedWebScheme = structure.webScheme
                adjustedWebView = true
            }

            // ── No WebView, pure native (or focused field not found) ── //
            else -> {
                // Nothing to isolate.
                return debugResult
            }
        }

        // Filter the resolved field types to only the allowed set.
        val filteredFieldTypes = result.fieldTypes.filterKeys { it in allowedIds }

        // Build the isolated structure with adjusted metadata.
        val isolatedStructure =
            structure.copy(
                webDomain = adjustedWebDomain,
                webScheme = adjustedWebScheme,
                webView = adjustedWebView,
            )

        return debugResult.copy(
            result =
                result.copy(
                    structure = isolatedStructure,
                    fieldTypes = filteredFieldTypes,
                ),
        )
    }
}
