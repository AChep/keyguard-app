package app.keemobile.kotpass.models

/** A qualified XML name retained from an unknown KDBX extension element. */
data class XmlQualifiedName(
    val localName: String,
    val namespaceUri: String = "",
    val prefix: String = "",
)

data class XmlNamespace(
    val prefix: String,
    val namespaceUri: String,
)

data class XmlAttribute(
    val name: XmlQualifiedName,
    val value: String,
)

/**
 * Lossless logical representation of an element not understood by this
 * library. Protected text remains protected in memory and is re-encrypted
 * in the correct output stream order when the database is saved.
 */
data class XmlExtension(
    val name: XmlQualifiedName,
    val namespaces: List<XmlNamespace> = emptyList(),
    val attributes: List<XmlAttribute> = emptyList(),
    val content: List<XmlExtensionContent> = emptyList(),
)

sealed interface XmlExtensionContent {
    data class Text(val value: EntryValue) : XmlExtensionContent
    data class Element(val value: XmlExtension) : XmlExtensionContent
    data class Comment(val value: String) : XmlExtensionContent
    data class ProcessingInstruction(
        val target: String,
        val data: String,
    ) : XmlExtensionContent
}
