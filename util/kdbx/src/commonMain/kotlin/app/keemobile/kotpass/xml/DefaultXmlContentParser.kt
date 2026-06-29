package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import org.redundent.kotlin.xml.PrintOptions
import org.redundent.kotlin.xml.XmlVersion
import org.redundent.kotlin.xml.parse
import org.redundent.kotlin.xml.xml

object DefaultXmlContentParser : XmlContentParser {
    private const val XmlEncoding = "utf-8"

    override fun unmarshalContent(
        xmlData: ByteArray,
        contextBlock: (Meta) -> XmlContext.Decode
    ): DatabaseContent {
        val documentNode = parse(xmlData)
        val rootNode = documentNode
            .firstOrNull(Tags.Root)
            ?: throw FormatError.InvalidXml("No root found.")
        val meta = documentNode
            .firstOrNull(Tags.Meta.TagName)
            ?.let(::unmarshalMeta)
            ?: throw FormatError.InvalidXml("No metadata found.")
        val rootGroup = rootNode
            .firstOrNull(Tags.Group.TagName)
            ?.let { unmarshalGroup(contextBlock(meta), it) }
            ?: throw FormatError.InvalidXml("No root group.")
        val deletedObjects = rootNode
            .firstOrNull(Tags.DeletedObjects.TagName)
            ?.childNodes()
            ?.filter { it.nodeName == Tags.DeletedObjects.Object }
            ?.mapNotNull(::unmarshalDeletedObject)
            ?: listOf()

        return DatabaseContent(meta, rootGroup, deletedObjects)
    }

    override fun marshalContent(
        context: XmlContext.Encode,
        content: DatabaseContent,
        pretty: Boolean
    ): String {
        return xml(Tags.Document, XmlEncoding, XmlVersion.V10) {
            addElement(content.meta.marshal(context))

            element(Tags.Root) {
                addElement(content.group.marshal(context))
                element(Tags.DeletedObjects.TagName) {
                    content.deletedObjects.forEach {
                        addElement(it.marshal(context))
                    }
                }
            }
        }.toString(
            PrintOptions(
                pretty = pretty,
                singleLineTextElements = true
            )
        )
    }
}
