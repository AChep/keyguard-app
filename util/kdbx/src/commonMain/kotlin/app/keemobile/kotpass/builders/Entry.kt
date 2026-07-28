package app.keemobile.kotpass.builders

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.TimeData
import kotlin.uuid.Uuid

internal class MutableEntry(
    var uuid: Uuid,
    var icon: PredefinedIcon = PredefinedIcon.Key,
    var customIconUuid: Uuid? = null,
    var foregroundColor: String? = null,
    var backgroundColor: String? = null,
    var overrideUrl: String = "",
    var times: TimeData? = null,
    var autoType: AutoTypeData? = null,
    var fields: MutableMap<String, EntryValue> = mutableMapOf(),
    var tags: MutableList<String> = mutableListOf(),
    var binaries: MutableList<BinaryReference> = mutableListOf(),
    var history: MutableList<Entry> = mutableListOf(),
    var customData: MutableMap<String, CustomDataValue> = mutableMapOf(),
    var previousParentGroup: Uuid? = null,
    var qualityCheck: Boolean = true
)

internal inline fun buildEntry(
    uuid: Uuid,
    crossinline block: MutableEntry.() -> Unit
): Entry = MutableEntry(uuid)
    .apply(block)
    .run {
        Entry(
            uuid = uuid,
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
            qualityCheck = qualityCheck
        )
    }
