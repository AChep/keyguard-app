package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.constants.PredefinedIcon
import kotlin.uuid.Uuid

data class Group(
    override val uuid: Uuid,
    val name: String,
    val notes: String = "",
    override val icon: PredefinedIcon = PredefinedIcon.Folder,
    override val customIconUuid: Uuid? = null,
    override val times: TimeData? = TimeData.create(),
    val expanded: Boolean = true,
    val defaultAutoTypeSequence: String? = null,
    val enableAutoType: GroupOverride = GroupOverride.Inherit,
    val enableSearching: GroupOverride = GroupOverride.Inherit,
    val lastTopVisibleEntry: Uuid? = null,
    val previousParentGroup: Uuid? = null,
    override val tags: List<String> = listOf(),
    val groups: List<Group> = listOf(),
    val entries: List<Entry> = listOf(),
    val customData: Map<String, CustomDataValue> = mapOf()
) : DatabaseElement {
    fun traverse(
        block: (DatabaseElement) -> Unit
    ) {
        val stack = ArrayDeque<Group>()
        stack.addLast(this)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            block(current)

            for (entry in current.entries) {
                block(entry)
            }
            for (group in current.groups) {
                stack.addLast(group)
            }
        }
    }

    fun findChildGroup(
        recycleBinUuid: Uuid? = null,
        predicate: (Group) -> Boolean
    ): Pair<Group, Group>? {
        val stack = ArrayDeque<Pair<Group, Group>>()
        groups
            .filter { recycleBinUuid == null || it.uuid.compareTo(recycleBinUuid) != 0 }
            .forEach { stack.addLast(this to it) }

        while (stack.isNotEmpty()) {
            val (parent, current) = stack.removeLast()

            if (predicate(current)) {
                return parent to current
            }
            current.groups
                .filter { recycleBinUuid == null || it.uuid.compareTo(recycleBinUuid) != 0 }
                .forEach { stack.addLast(current to it) }
        }

        return null
    }

    fun findChildEntry(
        useGroupOverride: Boolean = false,
        recycleBinUuid: Uuid? = null,
        predicate: (Entry) -> Boolean
    ): Pair<Group, Entry>? {
        val stack = ArrayDeque<Pair<Group, Boolean>>()
        stack.addLast(this to true)

        while (stack.isNotEmpty()) {
            val (current, parentSearchEnabled) = stack.removeLast()
            val searchEnabled = when (current.enableSearching) {
                GroupOverride.Inherit -> parentSearchEnabled
                GroupOverride.Enabled -> true
                GroupOverride.Disabled -> false
            }
            if (!useGroupOverride || searchEnabled) {
                for (entry in current.entries) {
                    if (predicate(entry)) {
                        return current to entry
                    }
                }
            }

            current.groups
                .filter { recycleBinUuid == null || it.uuid.compareTo(recycleBinUuid) != 0 }
                .forEach { stack.addLast(it to searchEnabled) }
        }

        return null
    }

    fun findChildEntries(
        useGroupOverride: Boolean = false,
        recycleBinUuid: Uuid? = null,
        predicate: (Entry) -> Boolean
    ): List<Pair<Group, List<Entry>>> {
        val result = mutableListOf<Pair<Group, List<Entry>>>()
        val stack = ArrayDeque<Pair<Group, Boolean>>()
        stack.addLast(this to true)

        while (stack.isNotEmpty()) {
            val (current, parentSearchEnabled) = stack.removeLast()
            val searchEnabled = when (current.enableSearching) {
                GroupOverride.Inherit -> parentSearchEnabled
                GroupOverride.Enabled -> true
                GroupOverride.Disabled -> false
            }
            if (!useGroupOverride || searchEnabled) {
                val found = current.entries.filter { predicate(it) }

                if (found.isNotEmpty()) {
                    result.add(current to found)
                }
            }

            current.groups
                .filter { recycleBinUuid == null || it.uuid.compareTo(recycleBinUuid) != 0 }
                .forEach { stack.addLast(it to searchEnabled) }
        }

        return result
    }

    companion object {
        /**
         * Creates [Group] with proper settings for Recycle Bin.
         */
        fun createRecycleBin(name: String) = Group(
            uuid = Uuid.random(),
            name = name,
            icon = PredefinedIcon.TrashBin,
            enableSearching = GroupOverride.Disabled,
            enableAutoType = GroupOverride.Disabled
        )
    }
}
