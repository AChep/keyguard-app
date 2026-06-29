package org.redundent.kotlin.xml

/**
 * Creates a new XML document with the specified root element name.
 *
 * @param root The root element name
 * @param encoding The encoding to use for the XML prolog
 * @param version The XML specification version to use for the xml prolog and attribute encoding
 * @param namespace Optional namespace object to use to build the name of the attribute. This will also add an `xmlns`
 * attribute for this value
 * @param init The block that defines the content of the XML
 */
fun xml(
    root: String,
    encoding: String? = null,
    version: XmlVersion? = null,
    namespace: Namespace? = null,
    init: (Node.() -> Unit)? = null
): Node {
    val node = Node(buildName(root, namespace))
    if (encoding != null) {
        node.encoding = encoding
    }

    if (version != null) {
        node.version = version
    }

    if (init != null) {
        node.init()
    }

    if (namespace != null) {
        node.namespace(namespace)
    }
    return node
}

/**
 * Creates a new XML document with the specified root element name.
 *
 * @param name The name of the element
 * @param init The block that defines the content of the XML
 */
fun node(name: String, namespace: Namespace? = null, init: (Node.() -> Unit)? = null): Node {
    val node = Node(buildName(name, namespace))
    if (init != null) {
        node.init()
    }
    return node
}

fun parse(data: ByteArray): Node = parse(data.decodeToString())

fun parse(xml: String): Node = XmlParser(xml).parse()

internal fun getLineEnding(printOptions: PrintOptions): String? {
    return if (printOptions.pretty) "\n" else ""
}

private class XmlParser(
    private val input: String
) {
    private var index = 0

    fun parse(): Node {
        skipMisc()
        val node = parseNode()
        skipMisc()
        return node
    }

    private fun parseNode(): Node {
        expect('<')
        val name = readName()
        val node = Node(name)

        while (true) {
            skipWhitespace()
            when {
                consume("/>") -> return node
                consume(">") -> break
                else -> {
                    val attrName = readName()
                    skipWhitespace()
                    expect('=')
                    skipWhitespace()
                    val attrValue = readQuotedValue()
                    if (attrName == "xmlns") {
                        node.namespace(Namespace(attrValue))
                    } else if (attrName.startsWith("xmlns:")) {
                        node.namespace(attrName.substringAfter("xmlns:"), attrValue)
                    } else {
                        node.attribute(attrName, attrValue)
                    }
                }
            }
        }

        while (index < input.length) {
            when {
                consume("</") -> {
                    val closingName = readName()
                    require(closingName == name) {
                        "Expected closing tag </$name>, found </$closingName>."
                    }
                    skipWhitespace()
                    expect('>')
                    return node
                }
                startsWith("<![CDATA[") -> {
                    index += "<![CDATA[".length
                    val end = input.indexOf("]]>", index)
                    require(end >= 0) { "Unterminated CDATA section." }
                    node.cdata(input.substring(index, end))
                    index = end + "]]>".length
                }
                startsWith("<!--") -> skipUntil("-->")
                startsWith("<?") -> skipUntil("?>")
                startsWith("<!") -> skipUntil(">")
                startsWith("<") -> node.addElement(parseNode())
                else -> {
                    val textStart = index
                    val nextTag = input.indexOf('<', index).let { if (it == -1) input.length else it }
                    index = nextTag
                    val text = input.substring(textStart, nextTag)
                        .trim { it.isWhitespace() || it == '\r' || it == '\n' }
                    if (text.isNotEmpty()) {
                        node.text(text.unescapeXml())
                    }
                }
            }
        }

        throw IllegalArgumentException("Unclosed tag <$name>.")
    }

    private fun skipMisc() {
        while (true) {
            skipWhitespace()
            when {
                startsWith("<?") -> skipUntil("?>")
                startsWith("<!--") -> skipUntil("-->")
                startsWith("<!") -> skipUntil(">")
                else -> return
            }
        }
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) {
            index++
        }
    }

    private fun readName(): String {
        val start = index
        while (index < input.length) {
            val c = input[index]
            if (c.isWhitespace() || c == '/' || c == '>' || c == '=') break
            index++
        }
        require(index > start) { "Expected XML name at offset $index." }
        return input.substring(start, index)
    }

    private fun readQuotedValue(): String {
        val quote = input.getOrNull(index)
        require(quote == '"' || quote == '\'') { "Expected quoted XML attribute at offset $index." }
        index++
        val start = index
        while (index < input.length && input[index] != quote) {
            index++
        }
        require(index < input.length) { "Unterminated XML attribute." }
        val value = input.substring(start, index).unescapeXml()
        index++
        return value
    }

    private fun expect(char: Char) {
        require(input.getOrNull(index) == char) {
            "Expected '$char' at offset $index."
        }
        index++
    }

    private fun consume(value: String): Boolean {
        if (!startsWith(value)) return false
        index += value.length
        return true
    }

    private fun startsWith(value: String): Boolean = input.startsWith(value, index)

    private fun skipUntil(value: String) {
        val end = input.indexOf(value, index)
        require(end >= 0) { "Unterminated XML section." }
        index = end + value.length
    }
}

private fun String.unescapeXml(): String = buildString(length) {
    var index = 0
    while (index < this@unescapeXml.length) {
        val c = this@unescapeXml[index]
        if (c != '&') {
            append(c)
            index++
            continue
        }

        val end = this@unescapeXml.indexOf(';', index + 1)
        if (end < 0) {
            append(c)
            index++
            continue
        }
        when (val entity = this@unescapeXml.substring(index + 1, end)) {
            "amp" -> append('&')
            "lt" -> append('<')
            "gt" -> append('>')
            "quot" -> append('"')
            "apos" -> append('\'')
            else -> {
                val code = when {
                    entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
                    entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                    else -> null
                }
                if (code != null) {
                    appendCodePoint(code)
                } else {
                    append('&').append(entity).append(';')
                }
            }
        }
        index = end + 1
    }
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val adjusted = codePoint - 0x10000
        append(((adjusted ushr 10) + 0xD800).toChar())
        append(((adjusted and 0x3FF) + 0xDC00).toChar())
    }
}
