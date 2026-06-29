package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.cleanupHistory
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.database.modifiers.removeEntry
import app.keemobile.kotpass.database.modifiers.removeGroup
import app.keemobile.kotpass.database.modifiers.removeUnusedBinaries
import app.keemobile.kotpass.database.modifiers.withRecycleBin
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import com.artemchep.keyguard.common.service.keepass.modifyEntryWithTimes
import okio.ByteString
import kotlin.uuid.Uuid

class KeePassDbMutator(
    initialDatabase: KeePassDatabase,
) {
    var database: KeePassDatabase = initialDatabase
        private set

    var mutationCount: Int = 0
        private set

    val hasMutations: Boolean get() = mutationCount > 0

    fun removeGroup(uuid: Uuid) {
        database = database.removeGroup(uuid)
        mutationCount++
    }

    /**
     * Removes the group identified by [uuid] while PRESERVING its contents.
     *
     * kotpass [removeGroup] cascades: it deletes the group together with every
     * descendant entry and sub-group. A folder deletion must not take the
     * user's ciphers (or any surviving sub-folders) down with it — on the
     * default "keep ciphers" delete path that would silently destroy live
     * credentials — so this first reparents every direct child entry and child
     * group to the root group ("no folder"), then removes the now-empty shell.
     *
     * Returns `false` when no group with [uuid] exists.
     */
    fun orphanAndRemoveGroup(uuid: Uuid): Boolean {
        val group = database.content.group.findChildGroup(uuid)
            ?: return false
        // Snapshot the child ids up front: each move below rebuilds the
        // database, but every operation locates its target by stable uuid.
        val childEntryUuids = group.entries.map(Entry::uuid)
        val childGroupUuids = group.groups.map(Group::uuid)
        childEntryUuids.forEach { entryUuid ->
            moveEntry(entryUuid, targetGroupUuid = null)
        }
        childGroupUuids.forEach { childGroupUuid ->
            moveGroup(childGroupUuid, targetParentGroupUuid = null)
        }
        removeGroup(uuid)
        return true
    }

    fun modifyGroup(uuid: Uuid, transform: Group.() -> Group): Boolean {
        var updated = false
        database = database.modifyGroup(uuid) {
            updated = true
            transform()
        }
        if (updated) mutationCount++
        return updated
    }

    fun addGroup(group: Group, parentGroupUuid: Uuid? = null) {
        if (parentGroupUuid != null) {
            database = database.modifyGroup(parentGroupUuid) {
                copy(groups = groups + group)
            }
        } else {
            database = database.modifyContent {
                val childGroups = this.group.groups + group
                val rootGroup = this.group.copy(groups = childGroups)
                copy(group = rootGroup)
            }
        }
        mutationCount++
    }

    fun removeEntry(uuid: Uuid) {
        database = database.removeEntry(uuid)
        mutationCount++
    }

    fun modifyEntry(uuid: Uuid, transform: Entry.() -> Entry): Boolean {
        var updated = false
        database = database.modifyEntryWithTimes(uuid) {
            updated = true
            transform()
        }
        if (updated) mutationCount++
        return updated
    }

    fun addEntry(entry: Entry, parentGroupUuid: Uuid? = null) {
        if (parentGroupUuid != null) {
            database = database.modifyGroup(parentGroupUuid) {
                copy(entries = entries + entry)
            }
        } else {
            database = database.modifyContent {
                val entries = buildList {
                    addAll(group.entries)
                    add(entry)
                }
                val rootGroup = group.copy(entries = entries)
                copy(group = rootGroup)
            }
        }
        mutationCount++
    }

    fun moveEntry(uuid: Uuid, targetGroupUuid: Uuid?): Boolean {
        val result = database.moveEntryToGroup(uuid, targetGroupUuid)
        database = result.database
        if (result.moved) mutationCount++
        return result.moved
    }

    fun moveGroup(uuid: Uuid, targetParentGroupUuid: Uuid?): Boolean {
        var moved = false
        database = database.modifyContent {
            val rootGroup = group
            val movingGroup = rootGroup.findChildGroup(uuid)
                ?: return@modifyContent this
            if (targetParentGroupUuid == uuid ||
                targetParentGroupUuid?.let(movingGroup::containsChildGroup) == true
            ) {
                return@modifyContent this
            }

            val currentParentUuid = rootGroup.findGroupParentUuid(uuid)
            if (currentParentUuid == targetParentGroupUuid) {
                return@modifyContent this
            }
            if (targetParentGroupUuid != null &&
                rootGroup.findChildGroup(targetParentGroupUuid) == null
            ) {
                return@modifyContent this
            }

            val removal = rootGroup.removeChildGroup(uuid)
            val groupToMove = removal.groupToMove
                ?: return@modifyContent this
            val newRootGroup = if (targetParentGroupUuid != null) {
                removal.rootGroup.addChildGroupToGroup(targetParentGroupUuid, groupToMove)
                    ?: return@modifyContent this
            } else {
                removal.rootGroup.copy(
                    groups = removal.rootGroup.groups + groupToMove,
                )
            }
            moved = true
            copy(group = newRootGroup)
        }
        if (moved) mutationCount++
        return moved
    }

    fun softDeleteEntry(uuid: Uuid) {
        database = database.withRecycleBin { recycleBinUuid ->
            moveEntryToGroup(uuid, recycleBinUuid).database
        }
        mutationCount++
    }

    fun cleanupHistory() {
        database = database.cleanupHistory()
    }

    fun addBinaries(additions: Map<ByteString, BinaryData>) {
        if (additions.isEmpty()) return
        database = database.modifyBinaries { binaries ->
            binaries + additions
        }
        mutationCount++
    }

    fun cleanupUnusedBinaries() {
        database = database.removeUnusedBinaries()
    }
}

private data class EntryRemoval(
    val group: Group,
    val entry: Entry?,
)

private data class EntryMoveResult(
    val database: KeePassDatabase,
    val moved: Boolean,
)

private data class GroupRemoval(
    val rootGroup: Group,
    val groupToMove: Group?,
)

private fun KeePassDatabase.moveEntryToGroup(
    uuid: Uuid,
    targetGroupUuid: Uuid?,
): EntryMoveResult {
    var moved = false
    val newDatabase = modifyContent {
        val rootGroup = group
        val currentParentUuid = rootGroup.findEntryParentUuid(uuid)
        if (currentParentUuid == targetGroupUuid) {
            return@modifyContent this
        }

        val removal = rootGroup.removeEntry(uuid)
        val entry = removal.entry
            ?: return@modifyContent this
        val newRootGroup = if (targetGroupUuid != null) {
            removal.group.addEntryToGroup(targetGroupUuid, entry)
                ?: return@modifyContent this
        } else {
            removal.group.copy(
                entries = removal.group.entries + entry,
            )
        }
        moved = true
        copy(group = newRootGroup)
    }
    return EntryMoveResult(
        database = newDatabase,
        moved = moved,
    )
}

private fun Group.findEntryParentUuid(
    entryUuid: Uuid,
    parentUuid: Uuid? = null,
): Uuid? {
    if (entries.any { it.uuid == entryUuid }) {
        return parentUuid
    }
    groups.forEach { child ->
        val result = child.findEntryParentUuid(entryUuid, child.uuid)
        if (result != null) return result
    }
    return null
}

private fun Group.removeEntry(entryUuid: Uuid): EntryRemoval {
    val entryIndex = entries.indexOfFirst { it.uuid == entryUuid }
    if (entryIndex >= 0) {
        val entry = entries[entryIndex]
        val newEntries = entries.toMutableList()
        newEntries.removeAt(entryIndex)
        return EntryRemoval(
            group = copy(entries = newEntries),
            entry = entry,
        )
    }

    var removedEntry: Entry? = null
    val newGroups = groups.map { child ->
        if (removedEntry != null) {
            child
        } else {
            val result = child.removeEntry(entryUuid)
            removedEntry = result.entry
            result.group
        }
    }
    return EntryRemoval(
        group = if (removedEntry != null) copy(groups = newGroups) else this,
        entry = removedEntry,
    )
}

private fun Group.addEntryToGroup(
    targetGroupUuid: Uuid,
    entry: Entry,
): Group? {
    if (uuid == targetGroupUuid) {
        return copy(entries = entries + entry)
    }

    var inserted = false
    val newGroups = groups.map { child ->
        if (inserted) {
            child
        } else {
            val updated = child.addEntryToGroup(targetGroupUuid, entry)
            if (updated != null) {
                inserted = true
                updated
            } else {
                child
            }
        }
    }
    return if (inserted) copy(groups = newGroups) else null
}

private fun Group.findChildGroup(groupUuid: Uuid): Group? {
    groups.forEach { child ->
        if (child.uuid == groupUuid) return child
        val descendant = child.findChildGroup(groupUuid)
        if (descendant != null) return descendant
    }
    return null
}

private fun Group.containsChildGroup(groupUuid: Uuid): Boolean =
    findChildGroup(groupUuid) != null

private fun Group.findGroupParentUuid(
    groupUuid: Uuid,
    parentUuid: Uuid? = null,
): Uuid? {
    groups.forEach { child ->
        if (child.uuid == groupUuid) return parentUuid
        val result = child.findGroupParentUuid(groupUuid, child.uuid)
        if (result != null) return result
    }
    return null
}

private fun Group.removeChildGroup(groupUuid: Uuid): GroupRemoval {
    val groupIndex = groups.indexOfFirst { it.uuid == groupUuid }
    if (groupIndex >= 0) {
        val groupToMove = groups[groupIndex]
        val newGroups = groups.toMutableList()
        newGroups.removeAt(groupIndex)
        return GroupRemoval(
            rootGroup = copy(groups = newGroups),
            groupToMove = groupToMove,
        )
    }

    var groupToMove: Group? = null
    val newGroups = groups.map { child ->
        if (groupToMove != null) {
            child
        } else {
            val result = child.removeChildGroup(groupUuid)
            groupToMove = result.groupToMove
            result.rootGroup
        }
    }
    return GroupRemoval(
        rootGroup = if (groupToMove != null) copy(groups = newGroups) else this,
        groupToMove = groupToMove,
    )
}

private fun Group.addChildGroupToGroup(
    targetGroupUuid: Uuid,
    groupToAdd: Group,
): Group? {
    if (uuid == targetGroupUuid) {
        return copy(groups = groups + groupToAdd)
    }

    var inserted = false
    val newGroups = groups.map { child ->
        if (inserted) {
            child
        } else {
            val updated = child.addChildGroupToGroup(targetGroupUuid, groupToAdd)
            if (updated != null) {
                inserted = true
                updated
            } else {
                child
            }
        }
    }
    return if (inserted) copy(groups = newGroups) else null
}
