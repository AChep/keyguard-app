package app.keemobile.kotpass.common

import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.core.XmlVersion

/**
 * Renders the XML fragment written by [block] to a [String] using
 * formatting compatible with XML snapshot data used in tests.
 *
 * - 4 spaces indent, compatible with `.editorconfig`.
 * - Maintains line break at the end.
 */
fun renderTestXmlString(block: (XmlWriter) -> Unit): String {
    val output = StringBuilder()
    val writer = KtXmlWriter(output, false, XmlDeclMode.None, XmlVersion.XML10)
    writer.addTrailingSpaceBeforeEnd = false
    writer.indentString = "    "
    block(writer)
    writer.close()
    return output.toString() + "\n"
}
