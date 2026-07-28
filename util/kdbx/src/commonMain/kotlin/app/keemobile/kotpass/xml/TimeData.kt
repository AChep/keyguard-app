package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.models.TimeData
import app.keemobile.kotpass.models.XmlContext
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import kotlin.time.Instant

internal fun unmarshalTimeData(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): TimeData {
    var creationTime: Instant? = null
    var lastAccessTime: Instant? = null
    var lastModificationTime: Instant? = null
    var locationChanged: Instant? = null
    var expiryTime: Instant? = null
    var expires = false
    var usageCount = 0

    reader.forEachChildElement {
        when {
            reader.isUnqualifiedElement(FormatXml.Tags.TimeData.CreationTime) ->
                creationTime = reader.readInstantOrNull()
            reader.isUnqualifiedElement(FormatXml.Tags.TimeData.LastAccessTime) ->
                lastAccessTime = reader.readInstantOrNull()
            reader.isUnqualifiedElement(FormatXml.Tags.TimeData.LastModificationTime) ->
                lastModificationTime = reader.readInstantOrNull()
            reader.isUnqualifiedElement(FormatXml.Tags.TimeData.LocationChanged) ->
                locationChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(FormatXml.Tags.TimeData.ExpiryTime) ->
                expiryTime = reader.readInstantOrNull()
            reader.isUnqualifiedElement(FormatXml.Tags.TimeData.Expires) ->
                expires = reader.readBooleanOrNull() ?: false
            reader.isUnqualifiedElement(FormatXml.Tags.TimeData.UsageCount) ->
                usageCount = reader.readIntOrNull() ?: 0
            else -> reader.discardKdbxElement(innerEncryption)
        }
    }
    return TimeData(
        creationTime = creationTime,
        lastAccessTime = lastAccessTime,
        lastModificationTime = lastModificationTime,
        locationChanged = locationChanged,
        expiryTime = expiryTime,
        expires = expires,
        usageCount = usageCount
    )
}

internal fun TimeData.marshalTo(
    context: XmlContext.Encode,
    writer: XmlWriter
) = writer.element(FormatXml.Tags.TimeData.TagName) {
    element(FormatXml.Tags.TimeData.CreationTime) { addDateTime(context, creationTime) }
    element(FormatXml.Tags.TimeData.LastAccessTime) { addDateTime(context, lastAccessTime) }
    element(FormatXml.Tags.TimeData.LastModificationTime) { addDateTime(context, lastModificationTime) }
    element(FormatXml.Tags.TimeData.LocationChanged) { addDateTime(context, locationChanged) }
    element(FormatXml.Tags.TimeData.ExpiryTime) { addDateTime(context, expiryTime) }
    element(FormatXml.Tags.TimeData.Expires) { addBoolean(expires) }
    element(FormatXml.Tags.TimeData.UsageCount) { text(usageCount.toString()) }
}
