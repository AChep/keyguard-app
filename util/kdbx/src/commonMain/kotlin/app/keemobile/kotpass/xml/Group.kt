package app.keemobile.kotpass.xml

import app.keemobile.kotpass.builders.buildGroup
import app.keemobile.kotpass.constants.Const
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addGroupOverride
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.childNodes
import app.keemobile.kotpass.extensions.getGroupOverride
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.getUuid
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

internal fun unmarshalGroup(
    context: XmlContext.Decode,
    node: Node
): Group {
    val uuid = node
        .firstOrNull(Tags.Uuid)
        ?.getUuid()
        ?: throw FormatError.InvalidXml("Invalid entry without Uuid.")

    return buildGroup(uuid) {
        for (childNode in node.childNodes()) {
            when (childNode.nodeName) {
                Tags.Group.Name -> {
                    name = childNode.getText() ?: ""
                }
                Tags.Group.Notes -> {
                    notes = childNode.getText() ?: ""
                }
                Tags.Group.IconId -> {
                    icon = childNode.getText()
                        ?.toInt()
                        ?.let(PredefinedIcon.entries::getOrNull)
                        ?: PredefinedIcon.Folder
                }
                Tags.Group.CustomIconId -> {
                    customIconUuid = childNode.getUuid()
                }
                Tags.TimeData.TagName -> {
                    times = unmarshalTimeData(childNode)
                }
                Tags.Group.IsExpanded -> {
                    expanded = childNode.getText().toBoolean()
                }
                Tags.Group.DefaultAutoTypeSequence -> {
                    defaultAutoTypeSequence = childNode.getText()
                }
                Tags.Group.EnableAutoType -> {
                    enableAutoType = childNode.getGroupOverride()
                }
                Tags.Group.EnableSearching -> {
                    enableSearching = childNode.getGroupOverride()
                }
                Tags.Group.LastTopVisibleEntry -> {
                    lastTopVisibleEntry = childNode.getUuid()
                }
                Tags.Group.PreviousParentGroup -> {
                    previousParentGroup = childNode.getUuid()
                }
                Tags.Group.Tags -> {
                    childNode
                        .getText()
                        ?.split(Const.TagsSeparatorsRegex)
                        ?.forEach(tags::add)
                }
                Tags.Group.TagName -> {
                    groups.add(unmarshalGroup(context, childNode))
                }
                Tags.Entry.TagName -> {
                    entries.add(unmarshalEntry(context, childNode))
                }
                Tags.CustomData.TagName -> {
                    customData = CustomData.unmarshal(childNode).toMutableMap()
                }
            }
        }
    }
}

internal fun Group.marshal(
    context: XmlContext.Encode
): Node = node(Tags.Group.TagName) {
    element(Tags.Uuid) { addUuid(uuid) }
    element(Tags.Group.Name) { text(name) }
    element(Tags.Group.Notes) { text(notes) }
    element(Tags.Group.IconId) { text(icon.ordinal.toString()) }
    if (customIconUuid != null) {
        element(Tags.Group.CustomIconId) { addUuid(customIconUuid) }
    }
    if (times != null) {
        addElement(times.marshal(context))
    }
    element(Tags.Group.IsExpanded) { addBoolean(expanded) }
    element(Tags.Group.DefaultAutoTypeSequence) { text(defaultAutoTypeSequence ?: "") }
    element(Tags.Group.EnableAutoType) { addGroupOverride(enableAutoType) }
    element(Tags.Group.EnableSearching) { addGroupOverride(enableSearching) }
    if (lastTopVisibleEntry != null) {
        element(Tags.Group.LastTopVisibleEntry) { addUuid(lastTopVisibleEntry) }
    }
    if (context.version.isAtLeast(4, 1) && previousParentGroup != null) {
        element(Tags.Group.PreviousParentGroup) { addUuid(previousParentGroup) }
    }
    if (context.version.isAtLeast(4, 1)) {
        element(Tags.Group.Tags) { text(tags.joinToString(Const.TagsSeparator)) }
    }
    if (customData.isNotEmpty()) {
        addElement(CustomData.marshal(context, customData))
    }
    groups.forEach { group -> addElement(group.marshal(context)) }
    entries.forEach { entry -> addElement(entry.marshal(context)) }
}
