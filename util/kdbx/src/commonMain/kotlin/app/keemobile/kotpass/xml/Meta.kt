package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.constants.MemoryProtectionFlag
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.getBytes
import app.keemobile.kotpass.extensions.getText
import app.keemobile.kotpass.extensions.getUuid
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.FormatXml.Tags
import okio.ByteString.Companion.toByteString
import org.redundent.kotlin.xml.Node
import org.redundent.kotlin.xml.node

internal fun unmarshalMeta(node: Node): Meta {
    return Meta(
        generator = node
            .firstOrNull(Tags.Meta.Generator)
            ?.getText()
            ?: Defaults.Generator,
        headerHash = node
            .firstOrNull(Tags.Meta.HeaderHash)
            ?.getBytes()
            ?.toByteString(),
        settingsChanged = node
            .firstOrNull(Tags.Meta.SettingsChanged)
            ?.getInstant(),
        name = node
            .firstOrNull(Tags.Meta.DatabaseName)
            ?.getText()
            ?: "",
        nameChanged = node
            .firstOrNull(Tags.Meta.DatabaseNameChanged)
            ?.getInstant(),
        description = node
            .firstOrNull(Tags.Meta.DatabaseDescription)
            ?.getText()
            ?: "",
        descriptionChanged = node
            .firstOrNull(Tags.Meta.DatabaseDescriptionChanged)
            ?.getInstant(),
        defaultUser = node
            .firstOrNull(Tags.Meta.DefaultUserName)
            ?.getText()
            ?: "",
        defaultUserChanged = node
            .firstOrNull(Tags.Meta.DefaultUserNameChanged)
            ?.getInstant(),
        maintenanceHistoryDays = node
            .firstOrNull(Tags.Meta.MaintenanceHistoryDays)
            ?.getText()
            ?.toUIntOrNull()
            ?: Defaults.MaintenanceHistoryDays,
        color = node
            .firstOrNull(Tags.Meta.Color)
            ?.getText(),
        masterKeyChanged = node
            .firstOrNull(Tags.Meta.MasterKeyChanged)
            ?.getInstant(),
        masterKeyChangeRec = node
            .firstOrNull(Tags.Meta.MasterKeyChangeRec)
            ?.getText()
            ?.toInt()
            ?: -1,
        masterKeyChangeForce = node
            .firstOrNull(Tags.Meta.MasterKeyChangeForce)
            ?.getText()
            ?.toInt()
            ?: -1,
        recycleBinEnabled = node
            .firstOrNull(Tags.Meta.RecycleBinEnabled)
            ?.getText().toBoolean(),
        recycleBinUuid = node
            .firstOrNull(Tags.Meta.RecycleBinUuid)
            ?.getUuid(),
        recycleBinChanged = node
            .firstOrNull(Tags.Meta.RecycleBinChanged)
            ?.getInstant(),
        entryTemplatesGroup = node
            .firstOrNull(Tags.Meta.EntryTemplatesGroup)
            ?.getUuid(),
        entryTemplatesGroupChanged = node
            .firstOrNull(Tags.Meta.EntryTemplatesGroupChanged)
            ?.getInstant(),
        historyMaxItems = node
            .firstOrNull(Tags.Meta.HistoryMaxItems)
            ?.getText()?.toInt()
            ?: Defaults.HistoryMaxItems,
        historyMaxSize = node
            .firstOrNull(Tags.Meta.HistoryMaxSize)
            ?.getText()?.toInt()
            ?: Defaults.HistoryMaxSize,
        lastSelectedGroup = node
            .firstOrNull(Tags.Meta.LastSelectedGroup)
            ?.getUuid(),
        lastTopVisibleGroup = node
            .firstOrNull(Tags.Meta.LastTopVisibleGroup)
            ?.getUuid(),
        memoryProtection = node
            .firstOrNull(Tags.Meta.MemoryProtection.TagName)
            ?.let(::unmarshalMemoryProtection)
            ?: setOf(),
        binaries = node.firstOrNull(Tags.Meta.Binaries.TagName)
            ?.let(::unmarshalBinaries)
            ?: linkedMapOf(),
        customIcons = node.firstOrNull(Tags.Meta.CustomIcons.TagName)
            ?.let(CustomIcons::unmarshal)
            ?: mapOf(),
        customData = node.firstOrNull(Tags.CustomData.TagName)
            ?.let(CustomData::unmarshal)
            ?: mapOf()
    )
}

private fun unmarshalMemoryProtection(node: Node): Set<MemoryProtectionFlag> =
    with(Tags.Meta.MemoryProtection) {
        return buildSet {
            if (node.firstOrNull(ProtectTitle)?.getText().toBoolean()) {
                add(MemoryProtectionFlag.Title)
            }
            if (node.firstOrNull(ProtectUserName)?.getText().toBoolean()) {
                add(MemoryProtectionFlag.UserName)
            }
            if (node.firstOrNull(ProtectPassword)?.getText().toBoolean()) {
                add(MemoryProtectionFlag.Password)
            }
            if (node.firstOrNull(ProtectUrl)?.getText().toBoolean()) {
                add(MemoryProtectionFlag.Url)
            }
            if (node.firstOrNull(ProtectNotes)?.getText().toBoolean()) {
                add(MemoryProtectionFlag.Notes)
            }
        }
    }

internal fun Meta.marshal(context: XmlContext.Encode): Node {
    return node(Tags.Meta.TagName) {
        element(Tags.Meta.Generator) { text(generator) }
        if (context.version.major < 4 && headerHash != null) {
            element(Tags.Meta.HeaderHash) { addBytes(headerHash.toByteArray()) }
        }
        if (settingsChanged != null && context.version.major >= 4) {
            element(Tags.Meta.SettingsChanged) { addDateTime(context, settingsChanged) }
        }
        element(Tags.Meta.DatabaseName) { text(name) }
        element(Tags.Meta.DatabaseNameChanged) { addDateTime(context, nameChanged) }
        element(Tags.Meta.DatabaseDescription) { text(description) }
        element(Tags.Meta.DatabaseDescriptionChanged) { addDateTime(context, descriptionChanged) }
        element(Tags.Meta.DefaultUserName) { text(defaultUser) }
        element(Tags.Meta.DefaultUserNameChanged) { addDateTime(context, defaultUserChanged) }
        element(Tags.Meta.MaintenanceHistoryDays) { text(maintenanceHistoryDays.toString()) }
        element(Tags.Meta.Color) { if (color != null) text(color) }
        element(Tags.Meta.MasterKeyChanged) { addDateTime(context, masterKeyChanged) }
        element(Tags.Meta.MasterKeyChangeRec) { text(masterKeyChangeRec.toString()) }
        element(Tags.Meta.MasterKeyChangeForce) { text(masterKeyChangeForce.toString()) }
        element(Tags.Meta.RecycleBinEnabled) { addBoolean(recycleBinEnabled) }
        element(Tags.Meta.RecycleBinUuid) { if (recycleBinUuid != null) addUuid(recycleBinUuid) }
        element(Tags.Meta.RecycleBinChanged) { addDateTime(context, recycleBinChanged) }
        element(Tags.Meta.EntryTemplatesGroup) {
            if (entryTemplatesGroup != null) addUuid(entryTemplatesGroup)
        }
        element(Tags.Meta.EntryTemplatesGroupChanged) {
            addDateTime(context, entryTemplatesGroupChanged)
        }
        element(Tags.Meta.HistoryMaxItems) { text(historyMaxItems.toString()) }
        element(Tags.Meta.HistoryMaxSize) { text(historyMaxSize.toString()) }
        element(Tags.Meta.LastSelectedGroup) {
            if (lastSelectedGroup != null) addUuid(lastSelectedGroup)
        }
        element(Tags.Meta.LastTopVisibleGroup) {
            if (lastTopVisibleGroup != null) addUuid(lastTopVisibleGroup)
        }
        addElement(marshalMemoryProtection(memoryProtection))
        addElement(CustomIcons.marshal(context, customIcons))
        addElement(CustomData.marshal(context, customData))

        // In version 4.x files are stored in binary inner header
        if (context.version.major < 4 || context is XmlContext.Encode.Plain) {
            element(Tags.Meta.Binaries.TagName) {
                var binaryCount = 0
                for ((_, binary) in context.binaries) {
                    addElement(binary.marshal(binaryCount))
                    binaryCount++
                }
            }
        }
    }
}

private fun marshalMemoryProtection(
    memoryProtection: Set<MemoryProtectionFlag>
): Node = node(Tags.Meta.MemoryProtection.TagName) {
    for (field in MemoryProtectionFlag.entries) {
        element(field.value) {
            addBoolean(memoryProtection.contains(field))
        }
    }
}
