package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.readUuidOrNull
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.TimeData
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.xml.FormatXml.Tags
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import kotlin.uuid.Uuid

internal fun unmarshalEntry(
    context: XmlContext.Decode,
    reader: XmlReader,
): Entry {
    val stack = ArrayDeque<EntryReadFrame>()
    stack.addLast(EntryReadFrame.EntryNode(EntryBuilder()))

    while (true) {
        when (reader.next()) {
            EventType.START_ELEMENT -> {
                when (val frame = stack.last()) {
                    is EntryReadFrame.EntryNode -> {
                        if (reader.isUnqualifiedElement(Tags.Entry.History)) {
                            stack.addLast(EntryReadFrame.History())
                        } else {
                            frame.builder.readChild(context, reader)
                        }
                    }

                    is EntryReadFrame.History -> {
                        if (reader.isUnqualifiedElement(Tags.Entry.TagName)) {
                            stack.addLast(EntryReadFrame.EntryNode(EntryBuilder()))
                        } else {
                            reader.discardKdbxElement(context.encryption)
                        }
                    }
                }
            }

            EventType.END_ELEMENT -> {
                when (val frame = stack.last()) {
                    is EntryReadFrame.EntryNode -> {
                        if (!reader.isUnqualifiedElement(Tags.Entry.TagName)) {
                            throw FormatError.InvalidXml(
                                "Unexpected closing element '${reader.localName}' in entry.",
                            )
                        }
                        stack.removeLast()
                        val entry = frame.builder.build(context)
                        if (stack.isEmpty()) return entry
                        val history = stack.last() as? EntryReadFrame.History
                            ?: throw FormatError.InvalidXml(
                                "Entry element is only allowed inside a History element.",
                            )
                        history.entries += entry
                    }

                    is EntryReadFrame.History -> {
                        if (!reader.isUnqualifiedElement(Tags.Entry.History)) {
                            throw FormatError.InvalidXml(
                                "Unexpected closing element '${reader.localName}' in history.",
                            )
                        }
                        stack.removeLast()
                        val entry = stack.lastOrNull() as? EntryReadFrame.EntryNode
                            ?: throw FormatError.InvalidXml(
                                "History element is only allowed inside an Entry element.",
                            )
                        entry.builder.history = frame.entries
                    }
                }
            }

            EventType.END_DOCUMENT ->
                throw FormatError.InvalidXml("Unexpected end of document.")

            else -> Unit
        }
    }
}

private sealed interface EntryReadFrame {
    data class EntryNode(
        val builder: EntryBuilder,
    ) : EntryReadFrame

    data class History(
        val entries: MutableList<Entry> = mutableListOf(),
    ) : EntryReadFrame
}

private class EntryBuilder {
    private var uuid: Uuid? = null
    private var icon = PredefinedIcon.Key
    private var customIconUuid: Uuid? = null
    private var foregroundColor: String? = null
    private var backgroundColor: String? = null
    private var overrideUrl = ""
    private var times: TimeData? = null
    private var autoType: AutoTypeData? = null
    private val fields = mutableMapOf<String, EntryValue>()
    private val untitledFields = mutableListOf<EntryValue>()
    private val tags = mutableListOf<String>()
    private val binaries = mutableListOf<BinaryReference>()
    var history: List<Entry> = emptyList()
    private var customData: Map<String, CustomDataValue> = emptyMap()
    private var previousParentGroup: Uuid? = null
    private var qualityCheck = true
    private val extensions = mutableListOf<XmlExtension>()

    fun readChild(
        context: XmlContext.Decode,
        reader: XmlReader,
    ) {
        when {
            reader.isUnqualifiedElement(Tags.Uuid) -> {
                uuid = reader.readUuidOrNull()
            }

            reader.isUnqualifiedElement(Tags.Entry.IconId) -> {
                icon = reader.readIntOrNull()
                    ?.let(PredefinedIcon.entries::getOrNull)
                    ?: PredefinedIcon.Key
            }

            reader.isUnqualifiedElement(Tags.Entry.CustomIconId) -> {
                customIconUuid = reader.readUuidOrNull()
            }

            reader.isUnqualifiedElement(Tags.Entry.ForegroundColor) -> {
                foregroundColor = reader.readElementTextOrNull()
            }

            reader.isUnqualifiedElement(Tags.Entry.BackgroundColor) -> {
                backgroundColor = reader.readElementTextOrNull()
            }

            reader.isUnqualifiedElement(Tags.Entry.OverrideUrl) -> {
                overrideUrl = reader.readElementTextOrNull() ?: ""
            }

            reader.isUnqualifiedElement(Tags.TimeData.TagName) -> {
                times = unmarshalTimeData(reader, context.encryption)
            }

            reader.isUnqualifiedElement(Tags.Entry.AutoType.TagName) -> {
                autoType = unmarshalAutoTypeData(reader, context.encryption)
            }

            reader.isUnqualifiedElement(Tags.Entry.Fields.TagName) -> {
                val (fieldName, value) = unmarshalField(context, reader)
                if (fieldName != null) {
                    fields[fieldName] = value
                } else {
                    untitledFields += value
                }
            }

            reader.isUnqualifiedElement(Tags.Entry.Tags) -> {
                reader.readElementTextOrNull()
                    ?.split(Const.TagsSeparatorsRegex)
                    ?.forEach(tags::add)
            }

            reader.isUnqualifiedElement(Tags.Entry.BinaryReferences.TagName) -> {
                unmarshalBinaryReference(context, reader)?.let(binaries::add)
            }

            reader.isUnqualifiedElement(Tags.CustomData.TagName) -> {
                customData = CustomData.unmarshal(reader, context.encryption)
            }

            reader.isUnqualifiedElement(Tags.Entry.PreviousParentGroup) -> {
                previousParentGroup = reader.readUuidOrNull()
            }

            reader.isUnqualifiedElement(Tags.Entry.QualityCheck) -> {
                qualityCheck = reader.readBooleanOrNull() ?: true
            }

            else -> extensions += reader.readExtension(context.encryption)
        }
    }

    fun build(context: XmlContext.Decode): Entry {
        if (untitledFields.isNotEmpty()) {
            recoverUntitledFields(context, fields, untitledFields)
        }
        return Entry(
            uuid = uuid ?: throw FormatError.InvalidXml("Invalid entry without Uuid."),
            icon = icon,
            customIconUuid = customIconUuid,
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            overrideUrl = overrideUrl,
            times = times,
            autoType = autoType,
            fields = EntryFields(fields),
            tags = tags,
            binaries = binaries,
            history = history,
            customData = customData,
            previousParentGroup = previousParentGroup,
            qualityCheck = qualityCheck,
            extensions = extensions,
        )
    }
}

/**
 * Recovers up to [UInt.MAX_VALUE] untitled fields.
 */
private fun recoverUntitledFields(
    context: XmlContext.Decode,
    fields: MutableMap<String, EntryValue>,
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

private fun unmarshalField(
    context: XmlContext.Decode,
    reader: XmlReader
): Pair<String?, EntryValue> {
    var key: String? = null
    var value: EntryValue? = null

    reader.forEachChildElement {
        when {
            reader.isUnqualifiedElement(Tags.Entry.Fields.ItemKey) -> {
                key = reader.readElementTextOrNull()
            }
            reader.isUnqualifiedElement(Tags.Entry.Fields.ItemValue) -> {
                value = reader.readProtectedXmlValue(context.encryption)
            }
            else -> reader.discardKdbxElement(context.encryption)
        }
    }
    return key to (value ?: EntryValue.Plain(""))
}

internal fun Entry.marshalTo(
    context: XmlContext.Encode,
    writer: XmlWriter
): Unit {
    val stack = ArrayDeque<EntryWriteTask>()
    stack.addLast(EntryWriteTask.Start(this))
    while (stack.isNotEmpty()) {
        when (val task = stack.removeLast()) {
            is EntryWriteTask.Start -> {
                val entry = task.entry
                writer.startTag("", Tags.Entry.TagName, "")
                entry.marshalOwnFields(context, writer)
                stack.addLast(EntryWriteTask.Finish(entry))
                if (entry.history.isNotEmpty()) {
                    writer.startTag("", Tags.Entry.History, "")
                    stack.addLast(EntryWriteTask.History(entry.history))
                }
            }
            is EntryWriteTask.History -> {
                val entry = task.entries[task.index++]
                if (task.index < task.entries.size) {
                    stack.addLast(task)
                } else {
                    stack.addLast(EntryWriteTask.FinishHistory)
                }
                stack.addLast(EntryWriteTask.Start(entry))
            }
            EntryWriteTask.FinishHistory -> writer.endTag("", Tags.Entry.History, "")
            is EntryWriteTask.Finish -> {
                task.entry.extensions.forEach { it.marshalTo(context, writer) }
                writer.endTag("", Tags.Entry.TagName, "")
            }
        }
    }
}

private sealed interface EntryWriteTask {
    data class Start(val entry: Entry) : EntryWriteTask
    data class Finish(val entry: Entry) : EntryWriteTask
    class History(
        val entries: List<Entry>,
        var index: Int = 0,
    ) : EntryWriteTask
    data object FinishHistory : EntryWriteTask
}

private fun Entry.marshalOwnFields(
    context: XmlContext.Encode,
    writer: XmlWriter,
) = with(writer) {
    element(Tags.Uuid) { addUuid(uuid) }
    element(Tags.Entry.IconId) { text(icon.ordinal.toString()) }
    if (customIconUuid != null) {
        element(Tags.Entry.CustomIconId) { addUuid(customIconUuid) }
    }
    element(Tags.Entry.ForegroundColor) {
        if (foregroundColor != null) verbatimText(foregroundColor)
    }
    element(Tags.Entry.BackgroundColor) {
        if (backgroundColor != null) verbatimText(backgroundColor)
    }
    element(Tags.Entry.OverrideUrl) { verbatimText(overrideUrl) }
    element(Tags.Entry.Tags) { verbatimText(tags.joinToString(Const.TagsSeparator)) }
    if (context.version.isAtLeast(4, 1)) {
        element(Tags.Entry.QualityCheck) { addBoolean(qualityCheck) }
    }
    if (context.version.isAtLeast(4, 1) && previousParentGroup != null) {
        element(Tags.Entry.PreviousParentGroup) { addUuid(previousParentGroup) }
    }
    if (times != null) {
        times.marshalTo(context, this)
    }
    marshalFields(context, fields, this)
    binaries.forEach {
        it.marshalTo(context, this)
    }
    if (customData.isNotEmpty()) {
        CustomData.marshalTo(context, customData, this)
    }
    if (autoType != null) {
        autoType.marshalTo(this)
    }
}

private fun marshalFields(
    context: XmlContext.Encode,
    fields: Map<String, EntryValue>,
    writer: XmlWriter
) {
    fields.forEach { (key, value) ->
        writer.element(Tags.Entry.Fields.TagName) {
            element(Tags.Entry.Fields.ItemKey) { verbatimText(key) }
            element(Tags.Entry.Fields.ItemValue) {
                writeProtectedXmlValue(
                    context = context,
                    value = value,
                    protectInMemory = value is EntryValue.Encrypted ||
                        (context is XmlContext.Encode.Plain &&
                            key in context.memoryProtectionKeys),
                )
            }
        }
    }
}
