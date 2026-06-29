package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import kotlin.time.Instant
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
 * Modifies a specific entry in the database.
 *
 * @param uuid The Uuid of the entry to modify.
 * @param block A lambda that takes [Entry] as a receiver and returns modified [Entry].
 * @return A new [KeePassDatabase] instance with the entry modified.
 */
fun KeePassDatabase.modifyEntry(
    uuid: Uuid,
    block: Entry.() -> Entry
) = modifyContent {
    copy(group = group.modifyEntry(uuid, block))
}

/**
 * Modifies all entries in the database.
 *
 * @param block A lambda that takes [Entry] as a receiver and returns modified [Entry].
 * @return A new [KeePassDatabase] instance with all entries modified.
 */
fun KeePassDatabase.modifyEntries(
    block: Entry.() -> Entry
) = modifyContent {
    copy(group = group.modifyEntries(block))
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
 * Modifies a specific entry within this group or its subgroups.
 *
 * @param uuid The Uuid of the entry to modify.
 * @param block A lambda that takes [Entry] as a receiver and returns modified [Entry].
 * @return A new [Group] instance with the entry modified.
 */
private fun Group.modifyEntry(
    uuid: Uuid,
    block: Entry.() -> Entry
): Group {
    val item = entries.find { it.uuid == uuid }

    return if (item != null) {
        val now = Clock.System.now()
        val modifiedEntry = block(item).copy(
            times = item.times?.copy(
                lastAccessTime = now,
                lastModificationTime = now
            ) ?: TimeData.create()
        )
        copy(entries = (entries - item) + modifiedEntry)
    } else {
        copy(groups = groups.map { it.modifyEntry(uuid, block) })
    }
}

/**
 * Modifies all entries within this group and its subgroups.
 *
 * @param block A lambda that takes [Entry] as a receiver and returns modified [Entry].
 * @return A new [Group] instance with all entries modified.
 */
private fun Group.modifyEntries(
    block: Entry.() -> Entry
): Group = copy(
    entries = entries.map { entry ->
        val newEntry = block(entry)

        if (newEntry != entry) {
            val now = Clock.System.now()
            newEntry.copy(
                times = entry.times?.copy(
                    lastAccessTime = now,
                    lastModificationTime = now
                ) ?: TimeData.create()
            )
        } else {
            newEntry
        }
    },
    groups = groups.map { it.modifyEntries(block) }
)

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
