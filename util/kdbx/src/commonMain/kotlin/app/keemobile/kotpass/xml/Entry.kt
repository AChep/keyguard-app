package app.keemobile.kotpass.xml

import app.keemobile.kotpass.builders.MutableEntry
import app.keemobile.kotpass.builders.buildEntry
import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.extensions.getBytes
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.getUuid
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

internal fun unmarshalEntry(
    context: XmlContext.Decode,
    node: Node
): Entry {
    val untitledFields = mutableListOf<EntryValue>()
    val uuid = node
        .firstOrNull(Tags.Uuid)
        ?.getUuid()
        ?: throw FormatError.InvalidXml("Invalid entry without Uuid.")

    return buildEntry(uuid) {
        for (childNode in node.childNodes()) {
            when (childNode.nodeName) {
                Tags.Entry.IconId -> {
                    icon = childNode
                        .getText()
                        ?.toInt()
                        ?.let(PredefinedIcon.entries::getOrNull)
                        ?: PredefinedIcon.Key
                }
                Tags.Entry.CustomIconId -> {
                    customIconUuid = childNode.getUuid()
                }
                Tags.Entry.ForegroundColor -> {
                    foregroundColor = childNode.getText()
                }
                Tags.Entry.BackgroundColor -> {
                    backgroundColor = childNode.getText()
                }
                Tags.Entry.OverrideUrl -> {
                    overrideUrl = childNode.getText() ?: ""
                }
                Tags.TimeData.TagName -> {
                    times = unmarshalTimeData(childNode)
                }
                Tags.Entry.AutoType.TagName -> {
                    autoType = unmarshalAutoTypeData(childNode)
                }
                Tags.Entry.Fields.TagName -> {
                    val (name, value) = unmarshalField(context, childNode)

                    if (name != null) {
                        fields[name] = value
                    } else {
                        untitledFields += value
                    }
                }
                Tags.Entry.Tags -> {
                    childNode
                        .getText()
                        ?.split(Const.TagsSeparatorsRegex)
                        ?.forEach(tags::add)
                }
                Tags.Entry.BinaryReferences.TagName -> {
                    unmarshalBinaryReference(context, childNode)?.let(binaries::add)
                }
                Tags.Entry.History -> {
                    history = unmarshalEntries(context, childNode).toMutableList()
                }
                Tags.CustomData.TagName -> {
                    customData = CustomData.unmarshal(childNode).toMutableMap()
                }
                Tags.Entry.PreviousParentGroup -> {
                    previousParentGroup = childNode.getUuid()
                }
                Tags.Entry.QualityCheck -> {
                    qualityCheck = childNode.getText()?.toBoolean() ?: true
                }
            }
        }

        if (untitledFields.isNotEmpty()) {
            recoverUntitledFields(context, untitledFields)
        }
    }
}

/**
 * Recovers up to [UInt.MAX_VALUE] untitled fields.
 */
private fun MutableEntry.recoverUntitledFields(
    context: XmlContext.Decode,
    untitledFields: List<EntryValue>
) {
    for (value in untitledFields) {
        var n = 1U
        var name = context.untitledLabel

        while (name in fields) {
            name = "${context.untitledLabel} ($n)"
            n++

            if (n == UInt.MAX_VALUE) return
        }

        fields[name] = value
    }
}

internal fun unmarshalEntries(
    context: XmlContext.Decode,
    node: Node
): List<Entry> = node
    .childNodes()
    .filter { it.nodeName == Tags.Entry.TagName }
    .map { unmarshalEntry(context, it) }

private fun unmarshalField(
    context: XmlContext.Decode,
    node: Node
): Pair<String?, EntryValue> {
    val key = node
        .firstOrNull(Tags.Entry.Fields.ItemKey)
        ?.getText()
    val protected = node
        .firstOrNull(Tags.Entry.Fields.ItemValue)
        ?.get<String?>(FormatXml.Attributes.Protected)
        .toBoolean()
    // Important when importing raw XML file
    val protectInMemory = node
        .firstOrNull(Tags.Entry.Fields.ItemValue)
        ?.get<String?>(FormatXml.Attributes.ProtectedInMemPlainXml)
        .toBoolean()

    return if (protected || protectInMemory) {
        val bytes = node
            .firstOrNull(Tags.Entry.Fields.ItemValue)
            ?.getBytes()
            ?: ByteArray(0)
        val salt = context.encryption.getSalt(bytes.size)

        key to EntryValue.Encrypted(EncryptedValue(bytes, salt))
    } else {
        val text = node
            .firstOrNull(Tags.Entry.Fields.ItemValue)
            ?.getText()
            ?: ""

        key to EntryValue.Plain(text)
    }
}

internal fun Entry.marshal(
    context: XmlContext.Encode
): Node = node(Tags.Entry.TagName) {
    element(Tags.Uuid) { addUuid(uuid) }
    element(Tags.Entry.IconId) { text(icon.ordinal.toString()) }
    if (customIconUuid != null) {
        element(Tags.Entry.CustomIconId) { addUuid(customIconUuid) }
    }
    element(Tags.Entry.ForegroundColor) {
        if (foregroundColor != null) text(foregroundColor)
    }
    element(Tags.Entry.BackgroundColor) {
        if (backgroundColor != null) text(backgroundColor)
    }
    element(Tags.Entry.OverrideUrl) { text(overrideUrl) }
    element(Tags.Entry.Tags) { text(tags.joinToString(Const.TagsSeparator)) }
    if (context.version.isAtLeast(4, 1)) {
        element(Tags.Entry.QualityCheck) { addBoolean(qualityCheck) }
    }
    if (context.version.isAtLeast(4, 1) && previousParentGroup != null) {
        element(Tags.Entry.PreviousParentGroup) { addUuid(previousParentGroup) }
    }
    if (times != null) {
        addElement(times.marshal(context))
    }
    marshalFields(context, fields).forEach {
        addElement(it)
    }
    binaries.forEach {
        addElement(it.marshal(context))
    }
    if (customData.isNotEmpty()) {
        addElement(CustomData.marshal(context, customData))
    }
    if (autoType != null) {
        addElement(autoType.marshal())
    }
    if (history.isNotEmpty()) {
        element(Tags.Entry.History) {
            history.forEach { addElement(it.marshal(context)) }
        }
    }
}

private fun marshalFields(
    context: XmlContext.Encode,
    fields: Map<String, EntryValue>
): List<Node> {
    return fields.map { (key, value) ->
        node(Tags.Entry.Fields.TagName) {
            element(Tags.Entry.Fields.ItemKey) { text(key) }
            element(Tags.Entry.Fields.ItemValue) {
                val isProtected = value is EntryValue.Encrypted

                when (context) {
                    is XmlContext.Encode.Encrypted -> {
                        if (isProtected) {
                            val encryptedContent = context
                                .innerEncryption
                                .processBytes(value.content.encodeToByteArray())

                            attribute(
                                FormatXml.Attributes.Protected,
                                FormatXml.Values.True
                            )
                            addBytes(encryptedContent)
                        } else {
                            text(value.content)
                        }
                    }
                    is XmlContext.Encode.Plain -> {
                        if (isProtected || key in context.memoryProtectionKeys) {
                            attribute(
                                FormatXml.Attributes.ProtectedInMemPlainXml,
                                FormatXml.Values.True
                            )
                        }
                        text(value.content)
                    }
                }
            }
        }
    }
}
