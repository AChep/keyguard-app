package com.artemchep.keyguard.util.webdav.internal

internal data class WebDavMultiStatusEntry(
    val href: String,
    val statusCode: Int?,
    val propStats: List<WebDavPropStat>,
)

internal data class WebDavPropStat(
    val statusCode: Int?,
    val properties: Map<XmlName, XmlNode>,
)

internal data class XmlName(
    val namespace: String?,
    val local: String,
) {
    fun isDav(
        local: String,
    ): Boolean = namespace == DAV_NAMESPACE && this.local == local
}

internal data class XmlNode(
    val name: XmlName,
    val children: List<XmlNode>,
    private val textParts: List<String>,
) {
    val directTextContent: String by lazy {
        textParts.joinToString(separator = "")
    }

    val textContent: String by lazy {
        buildString {
            textParts.forEach(::append)
            children.forEach { child ->
                append(child.textContent)
            }
        }
    }
}

internal object WebDavXml {
    val RESOURCETYPE = XmlName(DAV_NAMESPACE, "resourcetype")
    val COLLECTION = XmlName(DAV_NAMESPACE, "collection")
    val GET_CONTENT_LENGTH = XmlName(DAV_NAMESPACE, "getcontentlength")
    val GET_LAST_MODIFIED = XmlName(DAV_NAMESPACE, "getlastmodified")
    val GET_ETAG = XmlName(DAV_NAMESPACE, "getetag")

    fun propfindBody(): String = """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:propfind xmlns:D="DAV:">
          <D:prop>
            <D:resourcetype/>
            <D:getcontentlength/>
            <D:getlastmodified/>
            <D:getetag/>
          </D:prop>
        </D:propfind>
    """.trimIndent()

    fun parseMultiStatus(
        xml: String,
    ): List<WebDavMultiStatusEntry> {
        val root = XmlParser(xml).parse()
        if (!root.name.isDav("multistatus")) {
            throw IllegalArgumentException("Expected DAV:multistatus root element.")
        }

        return root.children
            .filter { it.name.isDav("response") }
            .map { response ->
                val href = response.children
                    .firstOrNull { it.name.isDav("href") }
                    ?.directTextContent
                    ?.trim()
                    .orEmpty()
                val statusCode = response.children
                    .firstOrNull { it.name.isDav("status") }
                    ?.directTextContent
                    ?.parseHttpStatusCode()
                val propStats = response.children
                    .filter { it.name.isDav("propstat") }
                    .map { propstat ->
                        val prop = propstat.children
                            .firstOrNull { it.name.isDav("prop") }
                        WebDavPropStat(
                            statusCode = propstat.children
                                .firstOrNull { it.name.isDav("status") }
                                ?.directTextContent
                                ?.parseHttpStatusCode(),
                            properties = prop
                                ?.children
                                ?.associateBy { it.name }
                                .orEmpty(),
                        )
                    }
                WebDavMultiStatusEntry(
                    href = href,
                    statusCode = statusCode,
                    propStats = propStats,
                )
            }
    }
}

internal fun String.parseHttpStatusCode(): Int? {
    val parts = trim().split(Regex("\\s+"), limit = 3)
    return parts
        .getOrNull(1)
        ?.toIntOrNull()
}

private class XmlParser(
    private val input: String,
) {
    private var index: Int = 0

    fun parse(): XmlNode {
        val root = MutableXmlNode(XmlName(null, "#document"))
        val stack = mutableListOf(
            XmlFrame(
                node = root,
                namespaces = mapOf("" to null),
            ),
        )

        while (index < input.length) {
            when {
                input.startsWith("<!--", index) -> skipUntil("-->")
                input.startsWith("<?", index) -> skipUntil("?>")
                input.startsWith("<![CDATA[", index) -> {
                    index += "<![CDATA[".length
                    stack.last().node.textParts += readUntil("]]>")
                }
                input.startsWith("<!DOCTYPE", index, ignoreCase = true) -> {
                    throw IllegalArgumentException("DOCTYPE declarations are not supported.")
                }
                input.startsWith("</", index) -> {
                    readEndTag()
                    if (stack.size > 1) {
                        stack.removeLast()
                    }
                }
                input[index] == '<' -> {
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
                        stack += XmlFrame(
                            node = node,
                            namespaces = namespaces,
                        )
                    }
                }
                else -> {
                    stack.last().node.textParts += decodeXmlEntities(readText())
                }
            }
        }

        val children = root.children
            .filterNot { child ->
                child.name.local == "#text" && child.textParts.all { it.isBlank() }
            }
        return children.singleOrNull()?.toImmutable()
            ?: root.toImmutable()
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

    private fun readEndTag() {
        index += 2
        readTagBody()
    }

    private fun readTagBody(): String {
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

    private fun parseAttributes(
        content: String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var cursor = 0
        while (cursor < content.length) {
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
            result[name] = decodeXmlEntities(content.substring(valueStart, cursor))
            cursor += 1
        }
        return result
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

    private fun readText(): String {
        val start = index
        while (index < input.length && input[index] != '<') {
            index += 1
        }
        return input.substring(start, index)
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

    private fun skipUntil(
        marker: String,
    ) {
        readUntil(marker)
    }
}

private data class XmlFrame(
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

private fun decodeXmlEntities(
    value: String,
): String = buildString {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '&') {
            append(char)
            index += 1
            continue
        }

        val end = value.indexOf(';', startIndex = index + 1)
        if (end < 0) {
            append(char)
            index += 1
            continue
        }

        val entity = value.substring(index + 1, end)
        val decoded = when (entity) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> decodeNumericEntity(entity)
        }
        if (decoded != null) {
            append(decoded)
        } else {
            append('&')
            append(entity)
            append(';')
        }
        index = end + 1
    }
}

private fun decodeNumericEntity(
    entity: String,
): String? {
    val codePoint = when {
        entity.startsWith("#x", ignoreCase = true) -> entity
            .drop(2)
            .toIntOrNull(radix = 16)
        entity.startsWith("#") -> entity
            .drop(1)
            .toIntOrNull()
        else -> null
    } ?: return null
    return runCatching {
        codePoint.toChar().toString()
    }.getOrNull()
}

private const val DAV_NAMESPACE = "DAV:"
