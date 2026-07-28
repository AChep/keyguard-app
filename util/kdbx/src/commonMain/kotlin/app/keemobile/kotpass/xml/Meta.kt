package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.constants.MemoryProtectionFlag
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.extensions.addBoolean
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.extensions.addDateTime
import app.keemobile.kotpass.extensions.addUuid
import app.keemobile.kotpass.extensions.readBytesOrNull
import app.keemobile.kotpass.extensions.readUuidOrNull
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.xml.FormatXml.Tags
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun unmarshalMeta(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): Meta {
    var generator: String? = null
    var headerHash: ByteString? = null
    var settingsChanged: Instant? = null
    var name = ""
    var nameChanged: Instant? = null
    var description = ""
    var descriptionChanged: Instant? = null
    var defaultUser = ""
    var defaultUserChanged: Instant? = null
    var maintenanceHistoryDays = Defaults.MaintenanceHistoryDays
    var color: String? = null
    var masterKeyChanged: Instant? = null
    var masterKeyChangeRec = -1
    var masterKeyChangeForce = -1
    var recycleBinEnabled = false
    var recycleBinUuid: Uuid? = null
    var recycleBinChanged: Instant? = null
    var entryTemplatesGroup: Uuid? = null
    var entryTemplatesGroupChanged: Instant? = null
    var historyMaxItems = Defaults.HistoryMaxItems
    var historyMaxSize = Defaults.HistoryMaxSize
    var lastSelectedGroup: Uuid? = null
    var lastTopVisibleGroup: Uuid? = null
    var memoryProtection: Set<MemoryProtectionFlag> = setOf()
    var binaries: Map<ByteString, BinaryData> = linkedMapOf()
    var customIcons: Map<Uuid, CustomIcon> = mapOf()
    var customData: Map<String, CustomDataValue> = mapOf()
    val extensions = mutableListOf<XmlExtension>()

    reader.forEachChildElement {
        when {
            reader.isUnqualifiedElement(Tags.Meta.Generator) ->
                generator = reader.readElementTextOrNull()
            reader.isUnqualifiedElement(Tags.Meta.HeaderHash) ->
                headerHash = reader.readBytesOrNull()?.toByteString()
            reader.isUnqualifiedElement(Tags.Meta.SettingsChanged) ->
                settingsChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(Tags.Meta.DatabaseName) ->
                name = reader.readElementTextOrNull() ?: ""
            reader.isUnqualifiedElement(Tags.Meta.DatabaseNameChanged) ->
                nameChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(Tags.Meta.DatabaseDescription) ->
                description = reader.readElementTextOrNull() ?: ""
            reader.isUnqualifiedElement(Tags.Meta.DatabaseDescriptionChanged) ->
                descriptionChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(Tags.Meta.DefaultUserName) ->
                defaultUser = reader.readElementTextOrNull() ?: ""
            reader.isUnqualifiedElement(Tags.Meta.DefaultUserNameChanged) ->
                defaultUserChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(Tags.Meta.MaintenanceHistoryDays) ->
                maintenanceHistoryDays = reader.readUIntOrNull() ?: Defaults.MaintenanceHistoryDays
            reader.isUnqualifiedElement(Tags.Meta.Color) ->
                color = reader.readElementTextOrNull()
            reader.isUnqualifiedElement(Tags.Meta.MasterKeyChanged) ->
                masterKeyChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(Tags.Meta.MasterKeyChangeRec) ->
                masterKeyChangeRec = reader.readIntOrNull() ?: -1
            reader.isUnqualifiedElement(Tags.Meta.MasterKeyChangeForce) ->
                masterKeyChangeForce = reader.readIntOrNull() ?: -1
            reader.isUnqualifiedElement(Tags.Meta.RecycleBinEnabled) ->
                recycleBinEnabled = reader.readBooleanOrNull() ?: false
            reader.isUnqualifiedElement(Tags.Meta.RecycleBinUuid) ->
                recycleBinUuid = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.Meta.RecycleBinChanged) ->
                recycleBinChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(Tags.Meta.EntryTemplatesGroup) ->
                entryTemplatesGroup = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.Meta.EntryTemplatesGroupChanged) ->
                entryTemplatesGroupChanged = reader.readInstantOrNull()
            reader.isUnqualifiedElement(Tags.Meta.HistoryMaxItems) ->
                historyMaxItems = reader.readIntOrNull() ?: Defaults.HistoryMaxItems
            reader.isUnqualifiedElement(Tags.Meta.HistoryMaxSize) ->
                historyMaxSize = reader.readIntOrNull() ?: Defaults.HistoryMaxSize
            reader.isUnqualifiedElement(Tags.Meta.LastSelectedGroup) ->
                lastSelectedGroup = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.Meta.LastTopVisibleGroup) ->
                lastTopVisibleGroup = reader.readUuidOrNull()
            reader.isUnqualifiedElement(Tags.Meta.MemoryProtection.TagName) ->
                memoryProtection = unmarshalMemoryProtection(reader, innerEncryption)
            reader.isUnqualifiedElement(Tags.Meta.Binaries.TagName) ->
                binaries = unmarshalBinaries(reader, innerEncryption)
            reader.isUnqualifiedElement(Tags.Meta.CustomIcons.TagName) ->
                customIcons = CustomIcons.unmarshal(reader, innerEncryption)
            reader.isUnqualifiedElement(Tags.CustomData.TagName) ->
                customData = CustomData.unmarshal(reader, innerEncryption)
            else -> extensions += reader.readExtension(innerEncryption)
        }
    }
    return Meta(
        generator = generator ?: Defaults.Generator,
        headerHash = headerHash,
        settingsChanged = settingsChanged,
        name = name,
        nameChanged = nameChanged,
        description = description,
        descriptionChanged = descriptionChanged,
        defaultUser = defaultUser,
        defaultUserChanged = defaultUserChanged,
        maintenanceHistoryDays = maintenanceHistoryDays,
        color = color,
        masterKeyChanged = masterKeyChanged,
        masterKeyChangeRec = masterKeyChangeRec,
        masterKeyChangeForce = masterKeyChangeForce,
        recycleBinEnabled = recycleBinEnabled,
        recycleBinUuid = recycleBinUuid,
        recycleBinChanged = recycleBinChanged,
        entryTemplatesGroup = entryTemplatesGroup,
        entryTemplatesGroupChanged = entryTemplatesGroupChanged,
        historyMaxItems = historyMaxItems,
        historyMaxSize = historyMaxSize,
        lastSelectedGroup = lastSelectedGroup,
        lastTopVisibleGroup = lastTopVisibleGroup,
        memoryProtection = memoryProtection,
        binaries = binaries,
        customIcons = customIcons,
        customData = customData,
        extensions = extensions,
    )
}

private fun unmarshalMemoryProtection(
    reader: XmlReader,
    innerEncryption: EncryptionSaltGenerator,
): Set<MemoryProtectionFlag> {
    val flags = mutableSetOf<MemoryProtectionFlag>()
    reader.forEachChildElement {
        val flag = when {
            reader.isUnqualifiedElement(Tags.Meta.MemoryProtection.ProtectTitle) ->
                MemoryProtectionFlag.Title
            reader.isUnqualifiedElement(Tags.Meta.MemoryProtection.ProtectUserName) ->
                MemoryProtectionFlag.UserName
            reader.isUnqualifiedElement(Tags.Meta.MemoryProtection.ProtectPassword) ->
                MemoryProtectionFlag.Password
            reader.isUnqualifiedElement(Tags.Meta.MemoryProtection.ProtectUrl) ->
                MemoryProtectionFlag.Url
            reader.isUnqualifiedElement(Tags.Meta.MemoryProtection.ProtectNotes) ->
                MemoryProtectionFlag.Notes
            else -> null
        }
        if (flag != null) {
            if (reader.readBooleanOrNull() == true) {
                flags.add(flag)
            }
        } else {
            reader.discardKdbxElement(innerEncryption)
        }
    }
    return flags
}

internal fun Meta.marshalTo(
    context: XmlContext.Encode,
    writer: XmlWriter
) {
    writer.element(Tags.Meta.TagName) {
        element(Tags.Meta.Generator) { verbatimText(generator) }
        if (context.version.major < 4 && headerHash != null) {
            element(Tags.Meta.HeaderHash) { addBytes(headerHash.toByteArray()) }
        }
        if (settingsChanged != null && context.version.major >= 4) {
            element(Tags.Meta.SettingsChanged) { addDateTime(context, settingsChanged) }
        }
        element(Tags.Meta.DatabaseName) { verbatimText(name) }
        element(Tags.Meta.DatabaseNameChanged) { addDateTime(context, nameChanged) }
        element(Tags.Meta.DatabaseDescription) { verbatimText(description) }
        element(Tags.Meta.DatabaseDescriptionChanged) { addDateTime(context, descriptionChanged) }
        element(Tags.Meta.DefaultUserName) { verbatimText(defaultUser) }
        element(Tags.Meta.DefaultUserNameChanged) { addDateTime(context, defaultUserChanged) }
        element(Tags.Meta.MaintenanceHistoryDays) { text(maintenanceHistoryDays.toString()) }
        element(Tags.Meta.Color) { if (color != null) verbatimText(color) }
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
        marshalMemoryProtection(memoryProtection, this)
        CustomIcons.marshalTo(context, customIcons, this)
        CustomData.marshalTo(context, customData, this)
        extensions.forEach { it.marshalTo(context, this) }

        // In version 4.x files are stored in binary inner header
        if (context.version.major < 4 || context is XmlContext.Encode.Plain) {
            element(Tags.Meta.Binaries.TagName) {
                for ((ref, _, binary) in context.binaryWritePlan.entries) {
                    binary.marshalTo(ref, this)
                }
            }
        }
    }
}

private fun marshalMemoryProtection(
    memoryProtection: Set<MemoryProtectionFlag>,
    writer: XmlWriter
) = writer.element(Tags.Meta.MemoryProtection.TagName) {
    for (field in MemoryProtectionFlag.entries) {
        element(field.value) {
            addBoolean(memoryProtection.contains(field))
        }
    }
}
