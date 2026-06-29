package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.CustomIcon
import kotlin.uuid.Uuid

/**
 * Replaces [Map] of [CustomIcon] while removing invalid references in entries.
 */
inline fun KeePassDatabase.modifyCustomIcons(
    crossinline block: (Map<Uuid, CustomIcon>) -> Map<Uuid, CustomIcon>
): KeePassDatabase {
    val newCustomIcons = block(content.meta.customIcons)
    val removedUuids = content.meta.customIcons.keys
        .filter { it !in newCustomIcons.keys }

    return modifyEntries {
        when (customIconUuid) {
            in removedUuids -> copy(customIconUuid = null)
            else -> this
        }
    }.modifyGroups {
        when (customIconUuid) {
            in removedUuids -> copy(customIconUuid = null)
            else -> this
        }
    }.modifyMeta {
        copy(customIcons = newCustomIcons)
    }
}
