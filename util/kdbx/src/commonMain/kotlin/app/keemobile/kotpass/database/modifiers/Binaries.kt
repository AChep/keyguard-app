package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Group
import okio.ByteString

/**
 * Returns a map of binary data associated with [KeePassDatabase].
 */
val KeePassDatabase.binaries
    get() = when (this) {
        is KeePassDatabase.Ver3x -> content.meta.binaries
        is KeePassDatabase.Ver4x -> innerHeader.binaries
    }

/**
 * Modifies binaries map of [KeePassDatabase] using the provided [block].
 *
 * @return A new [KeePassDatabase] instance with the modified binaries.
 */
inline fun KeePassDatabase.modifyBinaries(
    crossinline block: (Map<ByteString, BinaryData>) -> Map<ByteString, BinaryData>
): KeePassDatabase = when (this) {
    is KeePassDatabase.Ver3x -> modifyMeta {
        copy(binaries = block(content.meta.binaries))
    }
    is KeePassDatabase.Ver4x -> copy(
        innerHeader = innerHeader.copy(
            binaries = block(innerHeader.binaries)
        )
    )
}

/**
 * This function traverses all groups and entries, including historical entries,
 * to identify and remove unreferenced binary data.
 *
 * @return A new [KeePassDatabase] instance with unused binaries removed.
 */
fun KeePassDatabase.removeUnusedBinaries(): KeePassDatabase {
    val cleanupList = binaries.keys.toMutableSet()

    with(ArrayDeque<Group>()) {
        addLast(content.group)

        while (isNotEmpty()) {
            val group = removeLast()
            val combinedEntries = group
                .entries
                .flatMap { entry -> entry.history + entry }

            for (entry in combinedEntries) {
                cleanupList.removeAll(
                    entry.binaries.map(BinaryReference::hash)
                )
            }
            group.groups.forEach(::addLast)
        }
    }

    return modifyBinaries { it - cleanupList }
}
