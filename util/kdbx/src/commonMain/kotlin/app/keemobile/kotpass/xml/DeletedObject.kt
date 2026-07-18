package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.readUuidOrNull
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.XmlContext
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun unmarshalDeletedObjects(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): List<DeletedObject> {
    val objects = mutableListOf<DeletedObject>()
    reader.forEachChildElement {
        if (reader.isUnqualifiedElement(FormatXml.Tags.DeletedObjects.Object)) {
            unmarshalDeletedObject(reader, innerEncryption)?.let(objects::add)
        } else {
            reader.discardKdbxElement(innerEncryption)
        }
    }
    return objects
}

internal fun unmarshalDeletedObject(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): DeletedObject? {
    var uuid: Uuid? = null
    var dateTime: Instant? = null

    reader.forEachChildElement {
        when {
            reader.isUnqualifiedElement(FormatXml.Tags.Uuid) ->
                uuid = reader.readUuidOrNull()
            reader.isUnqualifiedElement(FormatXml.Tags.DeletedObjects.Time) ->
                dateTime = reader.readInstantOrNull()
            else -> reader.discardKdbxElement(innerEncryption)
        }
    }
    val objectUuid = uuid
    val objectTime = dateTime
    return if (objectUuid != null && objectTime != null) {
        DeletedObject(objectUuid, objectTime)
    } else {
        null
    }
}

internal fun DeletedObject.marshalTo(
    context: XmlContext.Encode,
    writer: XmlWriter
) = writer.element(FormatXml.Tags.DeletedObjects.Object) {
    element(FormatXml.Tags.Uuid) { addUuid(id) }
    element(FormatXml.Tags.DeletedObjects.Time) { addDateTime(context, deletionTime) }
}
