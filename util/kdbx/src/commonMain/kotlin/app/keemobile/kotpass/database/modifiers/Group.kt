package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getGroup
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Moves a group within [KeePassDatabase] to a new parent group.
 *
 * @param uuid The [Uuid] of the group to be moved.
 * @param parentGroup The [Uuid] of the target parent group where the group will be moved.
 * @return A new [KeePassDatabase] instance with the group moved.
 */
fun KeePassDatabase.moveGroup(
    uuid: Uuid,
    parentGroup: Uuid
): KeePassDatabase {
    if (content.group.uuid == uuid) {
        return this
    }
    val (previousParent, item) = getGroup { it.uuid == uuid } ?: return this

    return modifyParentGroup {
        removeChildGroup(uuid)
    }.modifyGroup(parentGroup) {
        copy(
            groups = groups + item.copy(
                times = item.times
                    ?.copy(locationChanged = Clock.System.now())
                    ?: TimeData.create(),
                previousParentGroup = previousParent?.uuid
            )
        )
    }
}

/**
 * Modifies the immediate children of the root group in [KeePassDatabase].
 *
 * @param block A lambda that transforms [Group] instance of the root group’s children.
 * @return A new [KeePassDatabase] instance with the modified root group children.
 */
fun KeePassDatabase.modifyParentGroup(
    block: Group.() -> Group
) = modifyContent {
    copy(group = group.modifyGroup(group.uuid, block))
}

/**
 * Modifies a specific group within [KeePassDatabase] identified by its [Uuid].
 *
 * @param uuid The [Uuid] of the group to be modified.
 * @param block A lambda that transforms the found [Group] instance.
 * @return A new [KeePassDatabase] instance with the modified group.
 */
fun KeePassDatabase.modifyGroup(
    uuid: Uuid,
    block: Group.() -> Group
) = modifyContent {
    copy(group = group.modifyGroup(uuid, block))
}

/**
 * Applies a modification block to all groups within [KeePassDatabase].
 *
 * @param block A lambda that transforms each [Group] instance.
 * @return A new [KeePassDatabase] instance with all groups potentially modified.
 */
fun KeePassDatabase.modifyGroups(
    block: Group.() -> Group
) = modifyContent {
    copy(group = group.modifyGroups(block))
}

/**
 * Removes a group and all its nested children and entries from [KeePassDatabase].
 *
 * @param uuid The [Uuid] of the group to be removed.
 * @return A new [KeePassDatabase] instance with the group and its contents removed.
 */
fun KeePassDatabase.removeGroup(
    uuid: Uuid
): KeePassDatabase {
    val now = Clock.System.now()
    val deletedUuids = (findGroupChildIds(uuid) + uuid)
        .map { DeletedObject(it, now) }
    return modifyContent {
        copy(
            group = group.removeChildGroup(uuid),
            deletedObjects = deletedUuids
        )
    }
}

/**
 * Finds all UUIDs of a given group and its direct and indirect
 * children (both groups and entries).
 *
 * @param uuid The [Uuid] of the group.
 * @return A [List] of [Uuid]s.
 */
private fun KeePassDatabase.findGroupChildIds(
    uuid: Uuid
): List<Uuid> {
    val uuids = mutableListOf<Uuid>()

    getGroup { it.uuid == uuid }?.let { (_, foundGroup) ->
        with(ArrayDeque<Group>()) {
            uuids.addAll(foundGroup.entries.map(Entry::uuid))
            foundGroup.groups.forEach(::addLast)

            while (isNotEmpty()) {
                val currentGroup = removeLast()
                uuids.add(currentGroup.uuid)
                uuids.addAll(currentGroup.entries.map(Entry::uuid))
                currentGroup.groups.forEach(::addLast)
            }
        }
    }

    return uuids
}

/**
 * Removes a child group from the current group’s hierarchy.
 *
 * @param uuid The [Uuid] of the child group to be removed.
 * @return A new [Group] instance with the specified child group removed.
 */
private fun Group.removeChildGroup(
    uuid: Uuid
): Group {
    return if (groups.find { it.uuid == uuid } != null) {
        copy(groups = groups.filter { it.uuid != uuid })
    } else {
        copy(groups = groups.map { it.removeChildGroup(uuid) })
    }
}

/**
 * Modifies a specific group within the current group’s hierarchy.
 *
 * @param uuid The [Uuid] of the group to be modified.
 * @param block A lambda that transforms the found [Group] instance.
 * @return A new [Group] instance with the specified group modified.
 */
private fun Group.modifyGroup(
    uuid: Uuid,
    block: Group.() -> Group
): Group {
    return if (this.uuid == uuid) {
        val now = Clock.System.now()
        block(this).copy(
            times = times?.copy(
                lastAccessTime = now,
                lastModificationTime = now
            ) ?: TimeData.create()
        )
    } else {
        copy(groups = groups.map { it.modifyGroup(uuid, block) })
    }
}

/**
 * Applies a modification block to all groups within the current group’s hierarchy.
 *
 * @param block A lambda that transforms each [Group] instance.
 * @return A new [Group] instance with all groups potentially modified.
 */
private fun Group.modifyGroups(
    block: Group.() -> Group
): Group {
    val newGroup = block(this)

    return when {
        newGroup != this -> {
            val now = Clock.System.now()
            newGroup.copy(
                times = times?.copy(
                    lastAccessTime = now,
                    lastModificationTime = now
                ) ?: TimeData.create()
            )
        }
        else -> newGroup
    }.copy(
        groups = groups.map { it.modifyGroups(block) }
    )
}
