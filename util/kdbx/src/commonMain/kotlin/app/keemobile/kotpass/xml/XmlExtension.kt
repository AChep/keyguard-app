package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.XmlAttribute
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import app.keemobile.kotpass.models.XmlNamespace
import app.keemobile.kotpass.models.XmlQualifiedName
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter

internal fun XmlReader.readExtension(
    innerEncryption: EncryptionSaltGenerator?,
): XmlExtension {
    val stack = ArrayDeque<ExtensionReadFrame>()
    stack.addLast(extensionReadFrame())

    while (true) {
        when (next()) {
            EventType.START_ELEMENT -> {
                val parent = stack.last()
                parent.requireUnprotectedContent()
                stack.addLast(extensionReadFrame())
            }
            EventType.TEXT,
            EventType.CDSECT,
            EventType.ENTITY_REF,
            EventType.IGNORABLE_WHITESPACE,
            -> stack.last().appendText(text)
            EventType.END_ELEMENT -> {
                val extension = stack.removeLast().build(innerEncryption)
                if (stack.isEmpty()) return extension
                stack.last().content += XmlExtensionContent.Element(extension)
            }
            EventType.COMMENT -> {
                val frame = stack.last()
                frame.requireUnprotectedContent()
                frame.content += XmlExtensionContent.Comment(text)
            }
            EventType.PROCESSING_INSTRUCTION -> {
                val frame = stack.last()
                frame.requireUnprotectedContent()
                frame.content += XmlExtensionContent.ProcessingInstruction(piTarget, piData)
            }
            EventType.END_DOCUMENT ->
                throw FormatError.InvalidXml("Unexpected end of document.")
            else -> Unit
        }
    }
}

private fun XmlReader.extensionReadFrame(): ExtensionReadFrame {
    val elementName = qualifiedName()
    val namespaces = namespaceDecls.map { XmlNamespace(it.prefix, it.namespaceURI) }
    val attributes = buildList {
        for (index in 0 until attributeCount) {
            add(
                XmlAttribute(
                    name = XmlQualifiedName(
                        localName = getAttributeLocalName(index),
                        namespaceUri = getAttributeNamespace(index),
                        prefix = getAttributePrefix(index),
                    ),
                    value = getAttributeValue(index),
                )
            )
        }
    }
    val markers = readProtectedXmlValueMarkers()
    val retainedAttributes = attributes.filterNot(XmlAttribute::isProtectedXmlValueMarker)
    return ExtensionReadFrame(
        elementName = elementName,
        namespaces = namespaces,
        attributes = retainedAttributes,
        markers = markers,
        maxTextChars = scalarCharLimit(),
    )
}

private class ExtensionReadFrame(
    val elementName: XmlQualifiedName,
    private val namespaces: List<XmlNamespace>,
    private val attributes: List<XmlAttribute>,
    private val markers: XmlProtectedValueMarkers,
    private val maxTextChars: Int,
) {
    val content = mutableListOf<XmlExtensionContent>()
    val protectedText = if (markers.isProtected) StringBuilder() else null
    private var textChars = 0

    fun requireUnprotectedContent() {
        if (protectedText != null) {
            throw FormatError.InvalidXml(
                "Protected extension element '${elementName.localName}' must contain text only."
            )
        }
    }

    fun appendText(value: String) {
        if (value.length > maxTextChars - textChars) {
            throw FormatError.InvalidXml(
                "Extension text exceeds the $maxTextChars-character limit."
            )
        }
        textChars += value.length
        if (protectedText != null) {
            protectedText.append(value)
        } else if (value.isNotEmpty()) {
            content += XmlExtensionContent.Text(EntryValue.Plain(value))
        }
    }

    fun build(innerEncryption: EncryptionSaltGenerator?): XmlExtension {
        if (protectedText != null) {
            val value = decodeProtectedXmlValue(
                text = protectedText.toString(),
                markers = markers,
                innerEncryption = innerEncryption,
                elementName = elementName.localName,
            )
            content += XmlExtensionContent.Text(value)
        }

        return XmlExtension(elementName, namespaces, attributes, content)
    }
}

private fun XmlReader.qualifiedName() = XmlQualifiedName(
    localName = localName,
    namespaceUri = namespaceURI,
    prefix = prefix,
)

internal fun XmlExtension.marshalTo(
    context: XmlContext.Encode,
    writer: XmlWriter,
) {
    val stack = ArrayDeque<ExtensionWriteTask>()
    stack.addLast(ExtensionWriteTask.Start(this))
    while (stack.isNotEmpty()) {
        when (val task = stack.removeLast()) {
            is ExtensionWriteTask.Start -> {
                val extension = task.extension
                val name = extension.name
                val protectedText = extension.protectedValueOrNull()
                val inheritedDefaultNamespace = writer.getNamespaceUri("")
                writer.startTag(name.namespaceUri, name.localName, name.prefix)
                extension.writeNamespaceDeclarations(writer, inheritedDefaultNamespace)
                extension.attributes
                    .filterNot(XmlAttribute::isProtectedXmlValueMarker)
                    .forEach { attribute ->
                        writer.attribute(
                            attribute.name.namespaceUri,
                            attribute.name.localName,
                            attribute.name.prefix,
                            attribute.value,
                        )
                    }

                stack.addLast(ExtensionWriteTask.Finish(name))
                if (protectedText != null) {
                    writer.writeProtectedXmlValue(
                        context = context,
                        value = protectedText,
                        protectInMemory = true,
                    )
                } else {
                    if (extension.content.isNotEmpty()) {
                        stack.addLast(ExtensionWriteTask.Content(extension.content))
                    }
                }
            }
            is ExtensionWriteTask.Content -> {
                val item = task.content[task.index++]
                if (task.index < task.content.size) {
                    stack.addLast(task)
                }
                when (item) {
                    is XmlExtensionContent.Comment -> writer.comment(item.value)
                    is XmlExtensionContent.Element ->
                        stack.addLast(ExtensionWriteTask.Start(item.value))
                    is XmlExtensionContent.ProcessingInstruction ->
                        writer.processingInstruction(item.target, item.data)
                    is XmlExtensionContent.Text -> writer.verbatimText(item.value.content)
                }
            }
            is ExtensionWriteTask.Finish ->
                writer.endTag(task.name.namespaceUri, task.name.localName, task.name.prefix)
        }
    }
}

private sealed interface ExtensionWriteTask {
    data class Start(val extension: XmlExtension) : ExtensionWriteTask
    class Content(
        val content: List<XmlExtensionContent>,
        var index: Int = 0,
    ) : ExtensionWriteTask
    data class Finish(val name: XmlQualifiedName) : ExtensionWriteTask
}

private fun XmlExtension.protectedValueOrNull(): EntryValue? {
    val onlyContent = content.singleOrNull()
    if (
        onlyContent is XmlExtensionContent.Text &&
        onlyContent.value is EntryValue.Encrypted
    ) {
        return onlyContent.value
    }
    val hasProtectedText = content.any { item ->
        item is XmlExtensionContent.Text && item.value is EntryValue.Encrypted
    }
    if (hasProtectedText) {
        throw FormatError.InvalidXml(
            "Protected extension '${name.localName}' must contain exactly one text value."
        )
    }
    return null
}

private fun XmlExtension.writeNamespaceDeclarations(
    writer: XmlWriter,
    inheritedDefaultNamespace: String?,
) {
    val bindings = linkedMapOf<String, String>()

    fun addBinding(prefix: String, namespaceUri: String) {
        val existing = bindings[prefix]
        if (existing != null && existing != namespaceUri) {
            throw FormatError.InvalidXml(
                "Extension '${name.localName}' binds prefix '$prefix' to both " +
                    "'$existing' and '$namespaceUri'."
            )
        }
        bindings[prefix] = namespaceUri
    }

    namespaces.forEach { addBinding(it.prefix, it.namespaceUri) }
    if (
        name.prefix.isEmpty() &&
        name.namespaceUri.isEmpty() &&
        !inheritedDefaultNamespace.isNullOrEmpty()
    ) {
        addBinding(prefix = "", namespaceUri = "")
    }
    name.requiredNamespaceBinding(isAttribute = false)?.let { binding ->
        addBinding(binding.prefix, binding.namespaceUri)
    }
    attributes.forEach { attribute ->
        attribute.name.requiredNamespaceBinding(isAttribute = true)?.let { binding ->
            addBinding(binding.prefix, binding.namespaceUri)
        }
    }

    bindings.forEach { (prefix, namespaceUri) ->
        if (prefix != XML_NAMESPACE_PREFIX) {
            writer.namespaceAttr(prefix, namespaceUri)
        }
    }
}

private fun XmlQualifiedName.requiredNamespaceBinding(
    isAttribute: Boolean,
): XmlNamespace? {
    if (namespaceUri.isEmpty()) {
        if (prefix.isNotEmpty()) {
            throw FormatError.InvalidXml(
                "Qualified name '$prefix:$localName' has no namespace URI."
            )
        }
        return null
    }
    if (prefix.isEmpty() && isAttribute) {
        throw FormatError.InvalidXml(
            "Namespaced attribute '$localName' must declare a prefix."
        )
    }
    if (prefix == XML_NAMESPACE_PREFIX && namespaceUri != XML_NAMESPACE_URI) {
        throw FormatError.InvalidXml(
            "Prefix '$XML_NAMESPACE_PREFIX' must use the standard XML namespace."
        )
    }
    return XmlNamespace(prefix, namespaceUri)
}

private const val XML_NAMESPACE_PREFIX = "xml"
private const val XML_NAMESPACE_URI = "http://www.w3.org/XML/1998/namespace"
