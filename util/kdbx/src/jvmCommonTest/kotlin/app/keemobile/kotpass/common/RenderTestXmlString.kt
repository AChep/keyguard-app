package app.keemobile.kotpass.common

import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.PrintOptions

private val Options = PrintOptions(
    pretty = true,
    singleLineTextElements = true,
    // 4 spaces, compatible with `.editorconfig`
    indent = "\u0020\u0020\u0020\u0020"
)

/**
 * Converts [node] to [String] using formatting compatible
 * with XML snapshot data used in tests.
 *
 * - Maintains line break at the end.
 */
fun renderTestXmlString(node: Node) = StringBuilder()
    .also { node.writeTo(it, Options) }
    .toString()
