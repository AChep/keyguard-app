package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import kotlin.uuid.Uuid

fun KeePassDatabase.modifyEntryWithTimes(
    uuid: Uuid,
    block: Entry.() -> Entry
) = modifyContent {
    copy(group = group.modifyEntryWithTimes(uuid, block))
}

fun Group.modifyEntryWithTimes(
    uuid: Uuid,
    block: Entry.() -> Entry
): Group {
    val entries = entries.toMutableList()
    val index = entries
        .indexOfFirst { it.uuid == uuid }
    return if (index >= 0) {
        val initialEntry = entries
            .removeAt(index)
        val modifiedEntry = block(initialEntry)
        // Add a new entry
        entries += modifiedEntry
        // Update the group
        copy(entries = entries)
    } else {
        val groups = groups
            .map { it.modifyEntryWithTimes(uuid, block) }
        copy(groups = groups)
    }
}
