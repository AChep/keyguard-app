package com.artemchep.keyguard.util.xml

/** Parses the XML subset used by remote storage responses; DTDs are unsupported. */
object XmlParser {
    /**
     * Parses one complete document, rejecting malformed input with [IllegalArgumentException].
     *
     * With [requireComplete] disabled, unfinished elements, mismatched end tags and text outside
     * the root are tolerated to recover partial error responses. Character, entity, attribute,
     * and depth checks still apply. If there is no single root, returns a `#document` node.
     */
    fun parse(
        xml: String,
        requireComplete: Boolean = true,
    ): XmlNode = Parser(xml).parse(requireComplete)
}

private class Parser(
    input: String,
) {
    // XML normalizes literal line endings before entity expansion, so &#13;
    // still represents a carriage return while a literal CR does not.
    private val input = input.also(::requireXmlCharacters)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    private var index: Int = 0

    fun parse(requireComplete: Boolean = true): XmlNode {
        val root = MutableXmlNode(XmlName(null, "#document"))
        val stack = mutableListOf(
            XmlFrame(
                rawName = "#document",
                node = root,
                namespaces = mapOf("" to null),
            ),
        )

        while (index < input.length) {
            readContent(stack, requireComplete)
        }

        if (requireComplete) {
            require(root.children.size == 1) { "Expected a single XML root element." }
            require(stack.size == 1) { "Incomplete XML document." }
            require(root.textParts.all { it.trim().removePrefix("\uFEFF").isBlank() }) {
                "Text outside the root element."
            }
        }
        return root.children.singleOrNull()?.toImmutable()
            ?: root.toImmutable()
    }

    private fun readContent(stack: MutableList<XmlFrame>, requireComplete: Boolean) {
        when {
            input.startsWith("<!--", index) -> readComment()
            input.startsWith("<?", index) -> readUntil("?>")
            input.startsWith("<![CDATA[", index) -> {
                index += "<![CDATA[".length
                stack.last().node.textParts += readUntil("]]>")
            }
            input.startsWith("<!DOCTYPE", index, ignoreCase = true) -> {
                throw IllegalArgumentException("DOCTYPE declarations are not supported.")
            }
            input.startsWith("</", index) -> {
                readEndElement(stack, requireComplete)
            }
            input[index] == '<' -> readStartElement(stack)
            else -> {
                stack.last().node.textParts += decodeXmlEntities(readText())
            }
        }
    }

    private fun readEndElement(stack: MutableList<XmlFrame>, requireComplete: Boolean) {
        val name = readEndTag()
        require(!requireComplete || stack.size > 1 && stack.last().rawName == name) {
            "Unexpected XML end tag."
        }
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
    }

    private fun readStartElement(stack: MutableList<XmlFrame>) {
        val tag = readStartTag()
        val namespaces = stack.last().namespaces.toMutableMap()
        tag.attributes.forEach { (name, value) ->
            when {
                name == "xmlns" -> namespaces[""] = value
                name.startsWith("xmlns:") -> {
                    namespaces[name.substringAfter(':')] = value
                }
            }
        }

        val node = MutableXmlNode(resolveName(tag.name, namespaces))
        stack.last().node.children += node
        if (!tag.selfClosing) {
            // Bound nesting because the tree is converted recursively.
            if (stack.size > MAX_XML_DEPTH) {
                throw IllegalArgumentException(
                    "XML document exceeds the $MAX_XML_DEPTH-level depth limit.",
                )
            }
            stack += XmlFrame(
                rawName = tag.name,
                node = node,
                namespaces = namespaces,
            )
        }
    }

    private fun readStartTag(): StartTag {
        index += 1
        val body = readTagBody()
        val trimmed = body.trim()
        val selfClosing = trimmed.endsWith("/")
        val content = if (selfClosing) {
            trimmed.dropLast(1).trimEnd()
        } else {
            trimmed
        }

        var cursor = 0
        while (cursor < content.length && !content[cursor].isWhitespace()) {
            cursor += 1
        }
        val name = content.substring(0, cursor)
        val attributes = parseAttributes(content.substring(cursor))
        return StartTag(
            name = name,
            attributes = attributes,
            selfClosing = selfClosing,
        )
    }

    private fun readEndTag(): String {
        index += 2
        return readTagBody().trim()
    }

    private fun readTagBody(): String {
        require(index < input.length && !input[index].isWhitespace()) {
            "Expected XML tag name immediately after '<' or '</'."
        }
        val start = index
        var quote: Char? = null
        while (index < input.length) {
            val char = input[index]
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char == '>' -> {
                    val result = input.substring(start, index)
                    index += 1
                    return result
                }
            }
            index += 1
        }
        throw IllegalArgumentException("Unterminated XML tag.")
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount")
    private fun parseAttributes(
        content: String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var cursor = 0
        while (cursor < content.length) {
            require(content[cursor].isWhitespace()) { "Expected whitespace before XML attribute." }
            while (cursor < content.length && content[cursor].isWhitespace()) {
                cursor += 1
            }
            if (cursor >= content.length) {
                break
            }

            val nameStart = cursor
            while (cursor < content.length && !content[cursor].isWhitespace() && content[cursor] != '=') {
                cursor += 1
            }
            val name = content.substring(nameStart, cursor)
            while (cursor < content.length && content[cursor].isWhitespace()) {
                cursor += 1
            }
            if (cursor >= content.length || content[cursor] != '=') {
                throw IllegalArgumentException("Expected '=' after XML attribute name.")
            }
            cursor += 1
            while (cursor < content.length && content[cursor].isWhitespace()) {
                cursor += 1
            }
            if (cursor >= content.length || content[cursor] !in setOf('"', '\'')) {
                throw IllegalArgumentException("Expected quoted XML attribute value.")
            }

            val quote = content[cursor]
            cursor += 1
            val valueStart = cursor
            while (cursor < content.length && content[cursor] != quote) {
                cursor += 1
            }
            if (cursor >= content.length) {
                throw IllegalArgumentException("Unterminated XML attribute value.")
            }
            val value = content.substring(valueStart, cursor)
            require('<' !in value) { "Unescaped '<' in XML attribute value." }
            require(name !in result) { "Duplicate XML attribute." }
            result[name] = decodeXmlEntities(value)
            cursor += 1
        }
        return result
    }

    private fun readText(): String {
        val start = index
        while (index < input.length && input[index] != '<') {
            index += 1
        }
        return input.substring(start, index).also {
            require("]]>" !in it) { "Unexpected CDATA end marker in XML text." }
        }
    }

    private fun readComment() {
        index += "<!--".length
        val comment = readUntil("-->")
        require("--" !in comment && !comment.endsWith('-')) { "Invalid XML comment." }
    }

    private fun readUntil(
        marker: String,
    ): String {
        val end = input.indexOf(marker, startIndex = index)
        if (end < 0) {
            throw IllegalArgumentException("Expected XML marker '$marker'.")
        }
        val result = input.substring(index, end)
        index = end + marker.length
        return result
    }
}

private data class XmlFrame(
    val rawName: String,
    val node: MutableXmlNode,
    val namespaces: Map<String, String?>,
)

private data class StartTag(
    val name: String,
    val attributes: Map<String, String>,
    val selfClosing: Boolean,
)

private class MutableXmlNode(
    val name: XmlName,
) {
    val children = mutableListOf<MutableXmlNode>()
    val textParts = mutableListOf<String>()

    fun toImmutable(): XmlNode = XmlNode(
        name = name,
        children = children.map { it.toImmutable() },
        textParts = textParts.toList(),
    )
}

private fun resolveName(
    rawName: String,
    namespaces: Map<String, String?>,
): XmlName {
    val prefix = rawName.substringBefore(':', missingDelimiterValue = "")
    val local = rawName.substringAfter(':')
    val namespace = if (':' in rawName) {
        namespaces[prefix]
    } else {
        namespaces[""]
    }
    return XmlName(
        namespace = namespace,
        local = local,
    )
}

private const val MAX_XML_DEPTH = 256
