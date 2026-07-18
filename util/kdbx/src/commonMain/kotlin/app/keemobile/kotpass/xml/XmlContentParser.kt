package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource

interface XmlContentParser {
    fun unmarshalContent(
        source: BufferedSource,
        innerEncryption: EncryptionSaltGenerator,
        contextBlock: (Meta) -> XmlContext.Decode,
    ): DatabaseContent

    fun marshalContentTo(
        context: XmlContext.Encode,
        content: DatabaseContent,
        sink: BufferedSink,
        pretty: Boolean = false,
    )
}

fun XmlContentParser.unmarshalContent(
    xmlData: ByteArray,
    innerEncryption: EncryptionSaltGenerator,
    contextBlock: (Meta) -> XmlContext.Decode,
): DatabaseContent = unmarshalContent(
    source = Buffer().write(xmlData),
    innerEncryption = innerEncryption,
    contextBlock = contextBlock,
)

fun XmlContentParser.marshalContent(
    context: XmlContext.Encode,
    content: DatabaseContent,
    pretty: Boolean = false,
): String = Buffer().apply {
    marshalContentTo(
        context = context,
        content = content,
        sink = this,
        pretty = pretty,
    )
}.readUtf8()
