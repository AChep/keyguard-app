package app.keemobile.kotpass.builders

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import kotlin.uuid.Uuid

internal class MutableGroup(
    var uuid: Uuid,
    var name: String = "",
    var notes: String = "",
    var icon: PredefinedIcon = PredefinedIcon.Folder,
    var customIconUuid: Uuid? = null,
    var times: TimeData? = null,
    var expanded: Boolean = true,
    var defaultAutoTypeSequence: String? = null,
    var enableAutoType: GroupOverride = GroupOverride.Inherit,
    var enableSearching: GroupOverride = GroupOverride.Inherit,
    var lastTopVisibleEntry: Uuid? = null,
    var previousParentGroup: Uuid? = null,
    var tags: MutableList<String> = mutableListOf(),
    var groups: MutableList<Group> = mutableListOf(),
    var entries: MutableList<Entry> = mutableListOf(),
    var customData: MutableMap<String, CustomDataValue> = mutableMapOf()
)

internal inline fun buildGroup(
    uuid: Uuid,
    crossinline block: MutableGroup.() -> Unit
): Group = MutableGroup(uuid)
    .apply(block)
    .run {
        Group(
            uuid = uuid,
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
            customData = customData
        )
    }
