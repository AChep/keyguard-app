package com.artemchep.keyguard.android.autofill.v2.extract

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View
import com.artemchep.keyguard.android.autofill.v2.model.ButtonNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseOptions

/**
 * Default [StructureExtractorV2] implementation that recursively traverses
 * each [AssistStructure] window's view tree.
 *
 * For every visible node it:
 * - collects `<input>`, `<textarea>`, and `<select>` elements as [FieldNode]s,
 * - collects `<button>` and submit-type inputs as [ButtonNode]s,
 * - collects native Android input views (EditText, SearchView, etc.) as [FieldNode]s,
 * - collects native Android buttons (Button, ImageButton, etc.) as [ButtonNode]s,
 * - tracks `<form>` boundaries to assign cluster IDs,
 * - skips known browser URL bars via [URL_BARS],
 * - skips assist-blocked and disabled nodes,
 * - extracts web domain/scheme from WebView nodes.
 */
class DefaultStructureExtractorV2 : StructureExtractorV2 {
    override fun extract(
        assistStructure: AssistStructure,
        options: ParseOptions,
    ): NormalizedStructureV2 {
        var applicationId: String? = null
        var webView = false
        var webScheme: String? = null
        var webDomain: String? = null
        val fields = mutableListOf<FieldNode>()
        val buttons = mutableListOf<ButtonNode>()
        val formActions = mutableMapOf<String, String?>()

        for (windowIndex in 0 until assistStructure.windowNodeCount) {
            val windowNode = assistStructure.getWindowNodeAt(windowIndex)
            applicationId = applicationId ?: windowNode.title
                .toString()
                .substringBefore("/")
                .takeUnless { it.contains(":") }

            traverse(
                node = windowNode.rootViewNode,
                pathStack = ArrayDeque<String>().apply { addLast("window-$windowIndex") },
                currentWebViewNodeId = null,
                currentClusterId = null,
                fields = fields,
                buttons = buttons,
                formActions = formActions,
            ).also { result ->
                webView = webView || result.webView
                webScheme = webScheme ?: result.webScheme
                webDomain = webDomain ?: result.webDomain
            }
        }

        return NormalizedStructureV2(
            applicationId = applicationId,
            webScheme = webScheme,
            webDomain = webDomain,
            webView = webView,
            fields = fields,
            buttons = buttons,
            formActions = formActions,
        )
    }

    private data class TraverseResult(
        val webView: Boolean,
        val webScheme: String?,
        val webDomain: String?,
    )

    private fun traverse(
        node: AssistStructure.ViewNode,
        pathStack: ArrayDeque<String>,
        currentWebViewNodeId: Int?,
        currentClusterId: String?,
        fields: MutableList<FieldNode>,
        buttons: MutableList<ButtonNode>,
        formActions: MutableMap<String, String?>,
    ): TraverseResult {
        fun traverseUrlOnly(
            node: AssistStructure.ViewNode,
        ): TraverseResult {
            val isWebView = node.className == "android.webkit.WebView"
            var nestedWebView = isWebView
            var nestedScheme: String? = if (Build.VERSION.SDK_INT >= 28) {
                node.webScheme?.takeIf { it.isNotEmpty() }
            } else null
            var nestedDomain: String? = node.webDomain
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i)
                val childSegment = "${child.className.orEmpty()}-$i"
                pathStack.addLast(childSegment)

                val childResult =
                    traverseUrlOnly(
                        node = child,
                    )
                pathStack.removeLast()
                nestedWebView = nestedWebView || childResult.webView
                nestedScheme = nestedScheme ?: childResult.webScheme
                nestedDomain = nestedDomain ?: childResult.webDomain
            }
            return TraverseResult(
                webView = nestedWebView,
                webScheme = nestedScheme,
                webDomain = nestedDomain,
            )
        }

        // Skip invisible and assist-blocked nodes. Disabled nodes are still
        // traversed so that disabled-but-visible fields (e.g. a pre-filled
        // identifier on a password-step screen) remain in the parsed structure
        // for save-detection.
        if (node.visibility != View.VISIBLE || node.isAssistBlocked) {
            return traverseUrlOnly(node)
        }

        // Skip known browser URL bars.
        if (isUrlBar(node)) {
            return traverseUrlOnly(node)
        }

        val isWebView = node.className == "android.webkit.WebView"
        val webViewNodeId = if (isWebView) node.id else currentWebViewNodeId

        val attributes =
            node.htmlInfo
                ?.attributes
                ?.associate { it.first.lowercase() to it.second }
                .orEmpty()
        val htmlTag = node.htmlInfo?.tag?.lowercase()

        // Whether this node lives outside a WebView (native Android view).
        val isNative = htmlTag == null && currentWebViewNodeId == null

        // Detect <form> boundaries to create separate clusters per form.
        val isFormTag = htmlTag == "form"
        val nextClusterId =
            when {
                isFormTag && webViewNodeId != null -> {
                    "cluster-form-$webViewNodeId-${pathStack.joinToString("-")}"
                }

                isWebView -> {
                    currentClusterId
                }

                webViewNodeId != null && currentClusterId == null -> {
                    "cluster-$webViewNodeId-${pathStack.last()}"
                }

                // Native views: derive cluster from the view hierarchy path.
                isNative && currentClusterId == null -> {
                    "cluster-native-${pathStack.joinToString("-")}"
                }

                else -> {
                    currentClusterId
                }
            }

        // Collect form action URL when we detect a <form> boundary.
        if (isFormTag && nextClusterId != null) {
            formActions[nextClusterId] = attributes["action"]
        }

        val htmlType = attributes["type"]?.lowercase()

        // Label: for native fields prefer hint > contentDescription;
        // for HTML fields use the existing priority chain.
        val label =
            firstNonBlank(
                node.hint,
                node.contentDescription?.toString(),
                attributes["label"],
                attributes["aria-label"],
                attributes["placeholder"],
                attributes["value"],
            )

        val autofillId = node.autofillId
        val autofillImportantForAutofill = if (Build.VERSION.SDK_INT >= 28) {
            node.importantForAutofill
        } else 0
        if (autofillId != null && (isFieldTag(htmlTag) || isNativeField(node, htmlTag))) {
            fields +=
                FieldNode(
                    id = autofillId,
                    index = fields.size,
                    label = label,
                    name =
                        firstNonBlank(
                            attributes["name"],
                            attributes["id"],
                            // For native fields, resource entry name acts as "name".
                            node.idEntry?.takeIf { isNative },
                        ),
                    viewHint = node.hint,
                    value =
                        node.autofillValue
                            ?.takeIf { it.isText }
                            ?.textValue
                            ?.toString(),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    htmlTag = htmlTag,
                    className = node.className,
                    inputType = node.inputType,
                    autofillType = node.autofillType,
                    importantForAutofill = autofillImportantForAutofill,
                    autofillHints = node.autofillHints?.toList().orEmpty(),
                    attributes = attributes,
                    parentWebViewNodeId = webViewNodeId,
                    clusterId = nextClusterId,
                    idPackage = node.idPackage,
                    idEntry = node.idEntry,
                    isNative = isNative,
                    isEnabled = node.isEnabled,
                )
        } else if (isButtonTag(htmlTag, htmlType) || isNativeButton(node, htmlTag)) {
            buttons +=
                ButtonNode(
                    index = buttons.size,
                    // For native buttons, text property is the primary label source.
                    label =
                        if (isNative) {
                            firstNonBlank(
                                node.text?.toString(),
                                node.hint,
                                node.contentDescription?.toString(),
                            )
                        } else {
                            label
                        },
                    name =
                        firstNonBlank(
                            attributes["name"],
                            attributes["id"],
                            node.idEntry?.takeIf { isNative },
                        ),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    htmlTag = htmlTag,
                    htmlType = htmlType,
                    className = node.className,
                    attributes = attributes,
                    clusterId = nextClusterId,
                )
        }

        var nestedWebView = isWebView
        var nestedScheme: String? = if (Build.VERSION.SDK_INT >= 28) {
            node.webScheme?.takeIf { it.isNotEmpty() }
        } else null
        var nestedDomain: String? = node.webDomain
            ?.takeIf { it.isNotEmpty() }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i)
            val childSegment = "${child.className.orEmpty()}-$i"
            pathStack.addLast(childSegment)
            val childResult =
                traverse(
                    node = child,
                    pathStack = pathStack,
                    currentWebViewNodeId = webViewNodeId,
                    currentClusterId = nextClusterId,
                    fields = fields,
                    buttons = buttons,
                    formActions = formActions,
                )
            pathStack.removeLast()
            nestedWebView = nestedWebView || childResult.webView
            nestedScheme = nestedScheme ?: childResult.webScheme
            nestedDomain = nestedDomain ?: childResult.webDomain
        }

        return TraverseResult(
            webView = nestedWebView,
            webScheme = nestedScheme,
            webDomain = nestedDomain,
        )
    }

    private fun isFieldTag(tag: String?): Boolean = tag in FIELD_TAGS

    /**
     * Detects native Android input views that should be treated as fillable fields.
     * Only matches when the node has no HTML info (i.e. not inside a WebView).
     */
    private fun isNativeField(
        node: AssistStructure.ViewNode,
        htmlTag: String?,
    ): Boolean {
        if (htmlTag != null) return false
        val cls = node.className ?: return node.autofillType != View.AUTOFILL_TYPE_NONE
        return cls.endsWith("EditText") ||
                cls.endsWith("AutoCompleteTextView") ||
                cls.endsWith("SearchView") ||
                cls.endsWith("Spinner") ||
                cls.endsWith("CompoundButton") ||
                cls.endsWith("CheckBox") ||
                cls.endsWith("RadioButton") ||
                cls.endsWith("Switch") ||
                cls.endsWith("ToggleButton") ||
                node.autofillType != View.AUTOFILL_TYPE_NONE
    }

    private fun isButtonTag(
        tag: String?,
        type: String?,
    ): Boolean = tag == "button" || (tag == "input" && type in BUTTON_SUBMIT_TYPES)

    /**
     * Detects native Android button views.
     * Only matches when the node has no HTML info.
     */
    private fun isNativeButton(
        node: AssistStructure.ViewNode,
        htmlTag: String?,
    ): Boolean {
        if (htmlTag != null) return false
        val cls = node.className ?: return false
        return cls.endsWith("Button") ||
                cls.endsWith("ImageButton") ||
                cls.endsWith("FloatingActionButton")
    }

    /**
     * Returns `true` if this node is a known browser URL bar that should be skipped.
     * Uses an exact match on (idPackage, idEntry) to avoid false positives.
     */
    private fun isUrlBar(node: AssistStructure.ViewNode): Boolean {
        val pkg = node.idPackage ?: return false
        val expectedEntry = URL_BARS[pkg] ?: return false
        return expectedEntry.equals(node.idEntry, ignoreCase = true)
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private companion object {
        private val FIELD_TAGS = setOf("input", "textarea", "select")
        private val BUTTON_SUBMIT_TYPES = setOf("submit", "button", "image")

        /**
         * Known browser URL bar (idPackage -> idEntry) pairs.
         * Matched views are skipped to prevent autofill in the address bar.
         */
        private val URL_BARS: Map<String, String> =
            mapOf(
                // Chrome
                "com.android.chrome" to "url_bar",
                "com.chrome.beta" to "url_bar",
                "com.chrome.dev" to "url_bar",
                "com.chrome.canary" to "url_bar",
                // Edge
                "com.microsoft.emmx" to "url_bar",
                "com.microsoft.emmx.beta" to "url_bar",
                "com.microsoft.emmx.canary" to "url_bar",
                "com.microsoft.emmx.dev" to "url_bar",
                // Samsung Internet
                "com.sec.android.app.sbrowser" to "location_bar_edit_text",
                "com.sec.android.app.sbrowser.beta" to "location_bar_edit_text",
                // Opera
                "com.opera.browser" to "url_bar",
                "com.opera.browser.beta" to "url_bar",
                // Brave
                "com.brave.browser" to "url_bar",
                "com.brave.browser_beta" to "url_bar",
                "com.brave.browser_nightly" to "url_bar",
            )
    }
}
