package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Moves an entry to a new parent group.
 *
 * @param uuid The Uuid of the entry to move.
 * @param parentGroup The Uuid of the destination parent group.
 * @return A new [KeePassDatabase] instance with the entry moved.
 */
fun KeePassDatabase.moveEntry(
    uuid: Uuid,
    parentGroup: Uuid
): KeePassDatabase {
    val (parent, item) = getEntry { it.uuid == uuid } ?: return this

    return modifyParentGroup {
        removeChildEntry(uuid)
    }.modifyGroup(parentGroup) {
        copy(
            entries = entries + item.copy(
                times = item.times
                    ?.copy(locationChanged = Clock.System.now())
                    ?: TimeData.create(),
                previousParentGroup = parent.uuid
            )
        )
    }
}

/**
 * Removes an entry from the database and adds it to the deleted objects list.
 *
 * @param uuid The Uuid of the entry to remove.
 * @return A new [KeePassDatabase] instance with the entry removed.
 */
fun KeePassDatabase.removeEntry(
    uuid: Uuid
) = modifyContent {
    copy(
        group = group.removeChildEntry(uuid),
        deletedObjects = deletedObjects + DeletedObject(uuid, Clock.System.now())
    )
}

/**
 * Creates a new entry with a historical record of the current entry.
 *
 * @param block A lambda that takes [Entry] as a receiver and returns modified [Entry].
 * @return A new [Entry] instance with the current entry added to its history.
 */
fun Entry.withHistory(
    block: Entry.() -> Entry
): Entry {
    val historicEntry = copy(history = listOf())
    return block().copy(
        history = history + historicEntry
    )
}

/**
 * Removes an entry from this group or its subgroups.
 *
 * @param uuid The Uuid of the entry to remove.
 * @return A new [Group] instance with the entry removed.
 */
private fun Group.removeChildEntry(
    uuid: Uuid
): Group {
    return if (entries.find { it.uuid == uuid } != null) {
        copy(entries = entries.filter { it.uuid != uuid })
    } else {
        copy(groups = groups.map { it.removeChildEntry(uuid) })
    }
}
