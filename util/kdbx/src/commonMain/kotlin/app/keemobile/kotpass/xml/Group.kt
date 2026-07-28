package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addGroupOverride
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.readGroupOverride
import app.keemobile.kotpass.extensions.readUuidOrNull
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.xml.FormatXml.Tags
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import kotlin.uuid.Uuid

/** Parses arbitrarily deep group trees without consuming the call stack. */
internal fun unmarshalGroup(
    context: XmlContext.Decode,
    reader: XmlReader,
): Group {
    val stack = ArrayDeque<GroupFrame>()
    stack.addLast(GroupFrame())

    while (true) {
        when (reader.next()) {
            EventType.START_ELEMENT -> {
                if (reader.isUnqualifiedElement(Tags.Group.TagName)) {
                    stack.addLast(GroupFrame())
                } else {
                    stack.last().readChild(context, reader)
                }
            }
            EventType.END_ELEMENT -> {
                if (!reader.isUnqualifiedElement(Tags.Group.TagName)) {
                    throw FormatError.InvalidXml(
                        "Unexpected closing element '${reader.localName}' in group."
                    )
                }
                val group = stack.removeLast().build()
                if (stack.isEmpty()) return group
                stack.last().groups += group
            }
            EventType.END_DOCUMENT ->
                throw FormatError.InvalidXml("Unexpected end of document.")
            else -> Unit
        }
    }
}

private class GroupFrame {
    private var uuid: Uuid? = null
    private var name = ""
    private var notes = ""
    private var icon = PredefinedIcon.Folder
    private var customIconUuid: Uuid? = null
    private var times: TimeData? = null
    private var expanded = true
    private var defaultAutoTypeSequence: String? = null
    private var enableAutoType = GroupOverride.Inherit
    private var enableSearching = GroupOverride.Inherit
    private var lastTopVisibleEntry: Uuid? = null
    private var previousParentGroup: Uuid? = null
    private val tags = mutableListOf<String>()
    val groups = mutableListOf<Group>()
    private val entries = mutableListOf<Entry>()
    private var customData: Map<String, CustomDataValue> = emptyMap()
    private val extensions = mutableListOf<XmlExtension>()

    fun readChild(context: XmlContext.Decode, reader: XmlReader) {
        when {
            reader.isUnqualifiedElement(Tags.Uuid) -> uuid = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.Group.Name) ->
                name = reader.readElementTextOrNull() ?: ""
            reader.isUnqualifiedElement(Tags.Group.Notes) ->
                notes = reader.readElementTextOrNull() ?: ""
            reader.isUnqualifiedElement(Tags.Group.IconId) -> {
                icon = reader.readIntOrNull()
                    ?.let(PredefinedIcon.entries::getOrNull)
                    ?: PredefinedIcon.Folder
            }
            reader.isUnqualifiedElement(Tags.Group.CustomIconId) ->
                customIconUuid = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.TimeData.TagName) ->
                times = unmarshalTimeData(reader, context.encryption)
            reader.isUnqualifiedElement(Tags.Group.IsExpanded) ->
                expanded = reader.readBooleanOrNull() ?: false
            reader.isUnqualifiedElement(Tags.Group.DefaultAutoTypeSequence) ->
                defaultAutoTypeSequence = reader.readElementTextOrNull()
            reader.isUnqualifiedElement(Tags.Group.EnableAutoType) ->
                enableAutoType = reader.readGroupOverride()
            reader.isUnqualifiedElement(Tags.Group.EnableSearching) ->
                enableSearching = reader.readGroupOverride()
            reader.isUnqualifiedElement(Tags.Group.LastTopVisibleEntry) ->
                lastTopVisibleEntry = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.Group.PreviousParentGroup) ->
                previousParentGroup = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.Group.Tags) ->
                reader.readElementTextOrNull()
                    ?.split(Const.TagsSeparatorsRegex)
                    ?.forEach(tags::add)
            reader.isUnqualifiedElement(Tags.Entry.TagName) ->
                entries += unmarshalEntry(context, reader)
            reader.isUnqualifiedElement(Tags.CustomData.TagName) ->
                customData = CustomData.unmarshal(reader, context.encryption)
            else -> extensions += reader.readExtension(context.encryption)
        }
    }

    fun build() = Group(
        uuid = uuid ?: throw FormatError.InvalidXml("Invalid group without Uuid."),
        name = name,
        notes = notes,
        icon = icon,
        customIconUuid = customIconUuid,
        times = times,
        expanded = expanded,
        defaultAutoTypeSequence = defaultAutoTypeSequence,
        enableAutoType = enableAutoType,
        enableSearching = enableSearching,
        lastTopVisibleEntry = lastTopVisibleEntry,
        previousParentGroup = previousParentGroup,
        tags = tags,
        groups = groups,
        entries = entries,
        customData = customData,
        extensions = extensions,
    )
}

internal fun Group.marshalTo(
    context: XmlContext.Encode,
    writer: XmlWriter,
) {
    val stack = ArrayDeque<GroupWriteTask>()
    stack.addLast(GroupWriteTask.Start(this))
    while (stack.isNotEmpty()) {
        when (val task = stack.removeLast()) {
            is GroupWriteTask.Start -> {
                val group = task.group
                writer.startTag("", Tags.Group.TagName, "")
                group.marshalOwnFields(context, writer)
                stack.addLast(GroupWriteTask.Finish(group))
                if (group.groups.isNotEmpty()) {
                    stack.addLast(GroupWriteTask.Children(group.groups))
                }
            }
            is GroupWriteTask.Children -> {
                val group = task.groups[task.index++]
                if (task.index < task.groups.size) {
                    stack.addLast(task)
                }
                stack.addLast(GroupWriteTask.Start(group))
            }
            is GroupWriteTask.Finish -> {
                task.group.entries.forEach { it.marshalTo(context, writer) }
                task.group.extensions.forEach { it.marshalTo(context, writer) }
                writer.endTag("", Tags.Group.TagName, "")
            }
        }
    }
}

private sealed interface GroupWriteTask {
    data class Start(val group: Group) : GroupWriteTask
    class Children(
        val groups: List<Group>,
        var index: Int = 0,
    ) : GroupWriteTask
    data class Finish(val group: Group) : GroupWriteTask
}

private fun Group.marshalOwnFields(context: XmlContext.Encode, writer: XmlWriter) = with(writer) {
    element(Tags.Uuid) { addUuid(uuid) }
    element(Tags.Group.Name) { verbatimText(name) }
    element(Tags.Group.Notes) { verbatimText(notes) }
    element(Tags.Group.IconId) { text(icon.ordinal.toString()) }
    if (customIconUuid != null) {
        element(Tags.Group.CustomIconId) { addUuid(customIconUuid) }
    }
    if (times != null) times.marshalTo(context, this)
    element(Tags.Group.IsExpanded) { addBoolean(expanded) }
    element(Tags.Group.DefaultAutoTypeSequence) { verbatimText(defaultAutoTypeSequence ?: "") }
    element(Tags.Group.EnableAutoType) { addGroupOverride(enableAutoType) }
    element(Tags.Group.EnableSearching) { addGroupOverride(enableSearching) }
    if (lastTopVisibleEntry != null) {
        element(Tags.Group.LastTopVisibleEntry) { addUuid(lastTopVisibleEntry) }
    }
    if (context.version.isAtLeast(4, 1) && previousParentGroup != null) {
        element(Tags.Group.PreviousParentGroup) { addUuid(previousParentGroup) }
    }
    if (context.version.isAtLeast(4, 1)) {
        element(Tags.Group.Tags) { verbatimText(tags.joinToString(Const.TagsSeparator)) }
    }
    if (customData.isNotEmpty()) CustomData.marshalTo(context, customData, this)
}
