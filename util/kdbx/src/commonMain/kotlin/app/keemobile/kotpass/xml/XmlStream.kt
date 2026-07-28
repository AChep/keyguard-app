package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.core.KtXmlReader
import nl.adaptivity.xmlutil.core.KtXmlWriter
import nl.adaptivity.xmlutil.core.XmlVersion
import okio.BufferedSink
import okio.BufferedSource

/**
 * Streaming XML primitives on top of xmlutil. The generic pure-Kotlin
 * reader and writer are used on every platform so that parsing and
 * output behave identically across JVM, Android and native targets.
 */

data class XmlReadLimits(
    val maxDocumentBytes: Long = 512L * 1024L * 1024L,
    val maxDepth: Int = 4_096,
    val maxElements: Long = 2_000_000L,
    val maxScalarChars: Int = 64 * 1024 * 1024,
    val maxAttributesPerElement: Int = 64,
    val maxAttributeCharsPerElement: Int = 1024 * 1024,
) {
    init {
        require(maxDocumentBytes > 0)
        require(maxDepth > 0)
        require(maxElements > 0)
        require(maxScalarChars > 0)
        require(maxAttributesPerElement >= 0)
        require(maxAttributeCharsPerElement >= 0)
    }

    companion object {
        val Default = XmlReadLimits()
    }
}

@OptIn(ExperimentalXmlUtilApi::class, XmlUtilInternal::class)
internal fun xmlReader(
    data: ByteArray,
    limits: XmlReadLimits = XmlReadLimits.Default,
): XmlReader = xmlReader(okio.Buffer().write(data), limits)

internal fun xmlReader(
    text: String,
    limits: XmlReadLimits = XmlReadLimits.Default,
): XmlReader = xmlReader(text.encodeToByteArray(), limits)

@OptIn(ExperimentalXmlUtilApi::class, XmlUtilInternal::class)
internal fun xmlReader(
    source: BufferedSource,
    limits: XmlReadLimits = XmlReadLimits.Default,
): XmlReader {
    // Entity expansion is deliberately disabled. OkioInOutBuffer feeds UTF-8
    // lazily, so large documents are not duplicated as an intermediate String.
    val delegate = KtXmlReader(
        OkioInOutBuffer(source, limits.maxDocumentBytes),
        encoding = "utf-8",
        relaxed = false,
        expandEntities = false,
    )
    return LimitedXmlReader(delegate, limits)
}

private class LimitedXmlReader(
    private val delegate: XmlReader,
    private val limits: XmlReadLimits,
) : XmlReader by delegate, ScalarLimitAware {
    private var elements = 0L

    override val maxScalarChars: Int
        get() = limits.maxScalarChars

    override fun next(): EventType {
        val event = delegate.next()
        when (event) {
            EventType.DOCDECL ->
                throw FormatError.InvalidXml("Document type declarations are not allowed.")
            EventType.START_ELEMENT -> {
                elements++
                if (elements > limits.maxElements) {
                    throw FormatError.InvalidXml(
                        "XML document exceeds the ${limits.maxElements}-element limit."
                    )
                }
                if (delegate.depth > limits.maxDepth) {
                    throw FormatError.InvalidXml(
                        "XML document exceeds the ${limits.maxDepth}-level depth limit."
                    )
                }
                if (delegate.attributeCount > limits.maxAttributesPerElement) {
                    throw FormatError.InvalidXml(
                        "Element '${delegate.localName}' has too many attributes."
                    )
                }
                var attributeChars = 0
                for (index in 0 until delegate.attributeCount) {
                    attributeChars += delegate.getAttributeLocalName(index).length
                    attributeChars += delegate.getAttributeValue(index).length
                    if (attributeChars > limits.maxAttributeCharsPerElement) {
                        throw FormatError.InvalidXml(
                            "Element '${delegate.localName}' has oversized attributes."
                        )
                    }
                }
            }
            else -> Unit
        }
        return event
    }
}

internal interface ScalarLimitAware {
    val maxScalarChars: Int
}

internal fun XmlReader.scalarCharLimit(): Int =
    (this as? ScalarLimitAware)?.maxScalarChars ?: Int.MAX_VALUE

/**
 * Advances the reader to the document's root element and returns its
 * name, or `null` if the document contains no element.
 */
internal fun XmlReader.enterDocumentRoot(): String? {
    while (hasNext()) {
        if (next() == EventType.START_ELEMENT) return localName
    }
    return null
}

/** Returns whether the current element is an unqualified KDBX schema element. */
internal fun XmlReader.isUnqualifiedElement(expectedLocalName: String): Boolean =
    namespaceURI.isEmpty() && localName == expectedLocalName

/**
 * Iterates over the child elements of the element the reader is
 * currently positioned at, consuming it through its end tag.
 *
 * [block] is invoked with the reader positioned at the child's start
 * tag and must fully consume the child element — either with one of
 * the `read*` helpers, a nested [forEachChildElement], or [skipElement]
 * for children it does not care about.
 */
internal inline fun XmlReader.forEachChildElement(block: XmlReader.() -> Unit) {
    while (true) {
        when (next()) {
            EventType.START_ELEMENT -> {
                val childName = localName
                val childNamespace = namespaceURI
                val childDepth = depth
                block()
                if (
                    eventType != EventType.END_ELEMENT ||
                    localName != childName ||
                    namespaceURI != childNamespace ||
                    depth != childDepth
                ) {
                    throw FormatError.InvalidXml(
                        "Reader callback did not fully consume element '$childName'."
                    )
                }
            }
            EventType.END_ELEMENT -> return
            EventType.END_DOCUMENT ->
                throw FormatError.InvalidXml("Unexpected end of document.")
            else -> Unit
        }
    }
}

/** Consumes the remainder of a document after its root element. */
internal fun XmlReader.finishDocument() {
    while (hasNext()) {
        when (next()) {
            EventType.END_DOCUMENT -> return
            EventType.COMMENT,
            EventType.PROCESSING_INSTRUCTION,
            EventType.IGNORABLE_WHITESPACE,
            -> Unit
            EventType.TEXT -> if (text.isNotBlank()) {
                throw FormatError.InvalidXml("Unexpected content after the document root.")
            }
            else -> throw FormatError.InvalidXml("Unexpected content after the document root.")
        }
    }
    if (eventType != EventType.END_DOCUMENT) {
        throw FormatError.InvalidXml("Unexpected end of document.")
    }
}

/**
 * Skips the element the reader is positioned at, including all of
 * its content, consuming it through its end tag.
 */
internal fun XmlReader.skipElement() {
    var depth = 0
    while (true) {
        when (next()) {
            EventType.START_ELEMENT -> depth++
            EventType.END_ELEMENT -> if (depth-- == 0) return
            EventType.END_DOCUMENT ->
                throw FormatError.InvalidXml("Unexpected end of document.")
            else -> Unit
        }
    }
}

/**
 * Reads the text content of the element the reader is positioned at,
 * consuming it through its end tag. Nested elements are rejected.
 * Returns `null` for an element with no text content at all, which
 * callers use to distinguish absent values from empty ones.
 */
internal fun XmlReader.readElementTextOrNull(): String? {
    val elementName = localName
    var singleValue: String? = null
    var result: StringBuilder? = null
    var resultLength = 0
    val maxChars = scalarCharLimit()
    while (true) {
        when (next()) {
            EventType.TEXT,
            EventType.CDSECT,
            EventType.ENTITY_REF,
            EventType.IGNORABLE_WHITESPACE,
            -> {
                val value = text
                if (value.length > maxChars - resultLength) {
                    throw FormatError.InvalidXml(
                        "Element text exceeds the $maxChars-character limit."
                    )
                }
                resultLength += value.length
                val builder = result
                when {
                    builder != null -> builder.append(value)
                    singleValue == null -> singleValue = value
                    else -> {
                        result = StringBuilder(resultLength)
                            .append(singleValue)
                            .append(value)
                    }
                }
            }
            EventType.START_ELEMENT -> throw FormatError.InvalidXml(
                "Element '$elementName' must contain text only."
            )
            EventType.END_ELEMENT -> return result?.toString() ?: singleValue
            EventType.END_DOCUMENT ->
                throw FormatError.InvalidXml("Unexpected end of document.")
            else -> Unit
        }
    }
}

internal fun XmlReader.readElementText(): String = readElementTextOrNull() ?: ""

/**
 * Returns the value of the unqualified [name] attribute of the current
 * element, or `null` when absent. Must be called before any of the
 * element's content is consumed.
 */
internal fun XmlReader.attributeOrNull(name: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeNamespace(i).isEmpty() && getAttributeLocalName(i) == name) {
            return getAttributeValue(i)
        }
    }
    return null
}

private fun createXmlWriter(output: Appendable, pretty: Boolean): KtXmlWriter =
    KtXmlWriter(
        output,
        true, // Repair namespace declarations for retained extension elements.
        XmlDeclMode.None,
        XmlVersion.XML10,
    ).apply {
        // Match the formatting of the previous serializer: empty elements
        // are written as `<Name/>` without a trailing space.
        addTrailingSpaceBeforeEnd = false
        if (pretty) indentString = "  "
    }

/**
 * Builds an XML document string with the KDBX prolog, streaming the
 * content written by [block] under a root element named [rootName].
 */
internal inline fun buildXmlString(
    rootName: String,
    pretty: Boolean = false,
    block: XmlWriter.() -> Unit,
): String {
    val output = StringBuilder()
    val writer = createXmlWriter(output, pretty)
    writer.startDocument("1.0", "utf-8", null)
    writer.element(rootName, block)
    writer.endDocument()
    writer.close()
    return output.toString()
}

internal fun writeXml(
    sink: BufferedSink,
    rootName: String,
    pretty: Boolean = false,
    block: XmlWriter.() -> Unit,
) {
    val output = BufferedSinkWriter(sink)
    val writer = createXmlWriter(output, pretty)
    writer.startDocument("1.0", "utf-8", null)
    writer.element(rootName, block)
    writer.endDocument()
    writer.close()
    output.flush()
}

private class BufferedSinkWriter(
    private val sink: BufferedSink,
) : Appendable {
    private val pending = StringBuilder(8_192)

    override fun append(value: Char): Appendable {
        pending.append(value)
        flushIfNeeded()
        return this
    }

    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable {
        pending.append(value, startIndex, endIndex)
        flushIfNeeded()
        return this
    }

    override fun append(value: CharSequence?): Appendable {
        pending.append(value)
        flushIfNeeded()
        return this
    }

    fun flush() {
        if (pending.isNotEmpty()) {
            sink.writeUtf8(pending.toString())
            pending.clear()
        }
        sink.flush()
    }

    private fun flushIfNeeded() {
        if (pending.length >= 8_192) {
            sink.writeUtf8(pending.toString())
            pending.clear()
        }
    }
}

/**
 * Writes an element named [name] whose content is produced by [block].
 */
internal inline fun XmlWriter.element(name: String, block: XmlWriter.() -> Unit = {}) {
    startTag("", name, "")
    block()
    endTag("", name, "")
}

/**
 * Writes an element named [name] holding the given [text].
 */
internal fun XmlWriter.textElement(name: String, text: String) {
    element(name) {
        if (text.isNotEmpty()) text(text)
    }
}

/**
 * Writes free-form text content, escaping carriage returns as
 * character references so they survive parsing — XML parsers
 * normalize raw CR and CRLF sequences to a single line feed.
 * This matches how KeePass 2.x writes multi-line values.
 */
internal fun XmlWriter.verbatimText(value: String) {
    var start = 0
    while (true) {
        val cr = value.indexOf('\r', start)
        if (cr < 0) break
        if (cr > start) {
            text(value.substring(start, cr))
        }
        entityRef("#xD")
        start = cr + 1
    }
    when {
        start == 0 -> text(value)
        start < value.length -> text(value.substring(start))
    }
}

/**
 * Writes a namespace-less attribute on the current element.
 */
internal fun XmlWriter.attribute(name: String, value: String) {
    attribute(null, name, null, value)
}
