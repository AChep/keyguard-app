package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import app.keemobile.kotpass.xml.FormatXml.Tags
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter

internal fun unmarshalAutoTypeData(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): AutoTypeData {
    var enabled = false
    var obfuscation = AutoTypeObfuscation.None
    var defaultSequence: String? = null
    val items = mutableListOf<AutoTypeItem>()

    reader.forEachChildElement {
        when {
            reader.isUnqualifiedElement(Tags.Entry.AutoType.Enabled) ->
                enabled = reader.readBooleanOrNull() ?: false
            reader.isUnqualifiedElement(Tags.Entry.AutoType.Obfuscation) ->
                obfuscation = reader.readIntOrNull()
                    ?.let(AutoTypeObfuscation.entries::getOrNull)
                    ?: AutoTypeObfuscation.None
            reader.isUnqualifiedElement(Tags.Entry.AutoType.DefaultSequence) ->
                defaultSequence = reader.readElementTextOrNull()
            reader.isUnqualifiedElement(Tags.Entry.AutoType.Association) ->
                unmarshalAutoTypeItem(reader, innerEncryption)?.let(items::add)
            else -> reader.discardKdbxElement(innerEncryption)
        }
    }
    return AutoTypeData(
        enabled = enabled,
        obfuscation = obfuscation,
        defaultSequence = defaultSequence,
        items = items
    )
}

private fun unmarshalAutoTypeItem(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): AutoTypeItem? {
    var window: String? = null
    var sequence: String? = null

    reader.forEachChildElement {
        when {
            reader.isUnqualifiedElement(Tags.Entry.AutoType.Window) ->
                window = reader.readElementTextOrNull()
            reader.isUnqualifiedElement(Tags.Entry.AutoType.KeystrokeSequence) ->
                sequence = reader.readElementTextOrNull()
            else -> reader.discardKdbxElement(innerEncryption)
        }
    }
    val itemWindow = window
    val itemSequence = sequence
    return if (itemWindow != null && itemSequence != null) {
        AutoTypeItem(itemWindow, itemSequence)
    } else {
        null
    }
}

internal fun AutoTypeData.marshalTo(
    writer: XmlWriter
) = writer.element(Tags.Entry.AutoType.TagName) {
    element(Tags.Entry.AutoType.Enabled) { addBoolean(enabled) }
    element(Tags.Entry.AutoType.Obfuscation) { text(obfuscation.ordinal.toString()) }
    element(Tags.Entry.AutoType.DefaultSequence) { verbatimText(defaultSequence ?: "") }

    for (item in items) {
        element(Tags.Entry.AutoType.Association) {
            element(Tags.Entry.AutoType.Window) { verbatimText(item.window) }
            element(Tags.Entry.AutoType.KeystrokeSequence) { verbatimText(item.keystrokeSequence) }
        }
    }
}
