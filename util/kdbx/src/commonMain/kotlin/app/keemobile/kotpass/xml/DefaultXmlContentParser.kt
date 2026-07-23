package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.xml.FormatXml.Tags
import nl.adaptivity.xmlutil.XmlException
import okio.BufferedSink
import okio.BufferedSource
import kotlin.coroutines.cancellation.CancellationException

class StreamingXmlContentParser(
    private val limits: XmlReadLimits = XmlReadLimits.Default,
) : XmlContentParser {
    override fun unmarshalContent(
        source: BufferedSource,
        innerEncryption: EncryptionSaltGenerator,
        contextBlock: (Meta) -> XmlContext.Decode,
    ): DatabaseContent {
        var meta: Meta? = null
        var rootGroup: Group? = null
        var deletedObjects: List<DeletedObject>? = null
        var sawRoot = false
        val documentExtensions = mutableListOf<XmlExtension>()
        val rootExtensions = mutableListOf<XmlExtension>()

        try {
            val reader = xmlReader(source, limits)
            reader.enterDocumentRoot()
                ?: throw FormatError.InvalidXml("No root found.")
            if (!reader.isUnqualifiedElement(Tags.Document)) {
                throw FormatError.InvalidXml("Unexpected document root '${reader.localName}'.")
            }
            reader.forEachChildElement {
                when {
                    reader.isUnqualifiedElement(Tags.Meta.TagName) && meta == null -> {
                        meta = unmarshalMeta(reader, innerEncryption)
                    }
                    reader.isUnqualifiedElement(Tags.Root) && !sawRoot -> {
                        sawRoot = true
                        val context = contextBlock(
                            meta ?: throw FormatError.InvalidXml("No metadata found.")
                        )
                        if (context.encryption !== innerEncryption) {
                            throw FormatError.InvalidXml(
                                "Decode context does not share the XML inner encryption stream."
                            )
                        }
                        reader.forEachChildElement {
                            when {
                                reader.isUnqualifiedElement(Tags.Group.TagName) &&
                                    rootGroup == null -> {
                                    rootGroup = unmarshalGroup(context, reader)
                                }
                                reader.isUnqualifiedElement(Tags.DeletedObjects.TagName) &&
                                    deletedObjects == null -> {
                                    deletedObjects = unmarshalDeletedObjects(
                                        reader,
                                        innerEncryption,
                                    )
                                }
                                else -> rootExtensions += reader.readExtension(innerEncryption)
                            }
                        }
                    }
                    else -> documentExtensions += reader.readExtension(innerEncryption)
                }
            }
            reader.finishDocument()
        } catch (e: FormatError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: XmlException) {
            throw FormatError.InvalidXml(
                e.message ?: "Malformed XML document.",
                e,
            )
        } catch (e: Exception) {
            throw FormatError.InvalidXml(
                e.message ?: "Malformed XML document.",
                e,
            )
        }

        if (!sawRoot) {
            throw FormatError.InvalidXml("No root found.")
        }
        return DatabaseContent(
            meta = meta ?: throw FormatError.InvalidXml("No metadata found."),
            group = rootGroup ?: throw FormatError.InvalidXml("No root group."),
            deletedObjects = deletedObjects ?: listOf(),
            documentExtensions = documentExtensions,
            rootExtensions = rootExtensions,
        )
    }

    override fun marshalContentTo(
        context: XmlContext.Encode,
        content: DatabaseContent,
        sink: BufferedSink,
        pretty: Boolean,
    ) {
        writeXml(sink, Tags.Document, pretty) {
            content.meta.marshalTo(context, this)

            element(Tags.Root) {
                content.group.marshalTo(context, this)
                element(Tags.DeletedObjects.TagName) {
                    content.deletedObjects.forEach {
                        it.marshalTo(context, this)
                    }
                }
                content.rootExtensions.forEach { it.marshalTo(context, this) }
            }
            content.documentExtensions.forEach { it.marshalTo(context, this) }
        }
    }
}

object DefaultXmlContentParser : XmlContentParser by StreamingXmlContentParser()
