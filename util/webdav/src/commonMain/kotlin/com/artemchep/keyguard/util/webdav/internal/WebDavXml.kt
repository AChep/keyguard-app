package com.artemchep.keyguard.util.webdav.internal

import com.artemchep.keyguard.util.xml.XmlName
import com.artemchep.keyguard.util.xml.XmlNode
import com.artemchep.keyguard.util.xml.XmlParser

internal data class WebDavMultiStatusEntry(
    val href: String,
    val statusCode: Int?,
    val propStats: List<WebDavPropStat>,
)

internal data class WebDavPropStat(
    val statusCode: Int?,
    val properties: Map<XmlName, XmlNode>,
)

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
        val root = XmlParser.parse(xml)
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

private fun XmlName.isDav(
    local: String,
): Boolean = namespace == DAV_NAMESPACE && this.local == local

private const val DAV_NAMESPACE = "DAV:"
