package com.artemchep.keyguard.util.xml

data class XmlNode(
    val name: XmlName,
    val children: List<XmlNode>,
    private val textParts: List<String>,
) {
    /** Text directly inside this element, excluding child elements. */
    val directTextContent: String by lazy {
        textParts.joinToString(separator = "")
    }

    /** Direct text followed by each child's text, recursively. */
    val textContent: String by lazy {
        buildString {
            textParts.forEach(::append)
            children.forEach { child ->
                append(child.textContent)
            }
        }
    }
}
