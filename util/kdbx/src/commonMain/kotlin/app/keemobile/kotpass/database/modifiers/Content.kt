package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.constants.Defaults
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.extensions.isNullOrZero
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

inline fun KeePassDatabase.modifyContent(
    crossinline block: DatabaseContent.() -> DatabaseContent
) = when (this) {
    is KeePassDatabase.Ver3x -> copy(content = block(content))
    is KeePassDatabase.Ver4x -> copy(content = block(content))
}

inline fun KeePassDatabase.withRecycleBin(
    crossinline block: KeePassDatabase.(recycleBinUuid: Uuid) -> KeePassDatabase
): KeePassDatabase {
    return if (!content.meta.recycleBinUuid.isNullOrZero()) {
        block(this, content.meta.recycleBinUuid!!)
    } else {
        val recycleBin = Group.createRecycleBin(Defaults.RecycleBinName)

        modifyContent {
            copy(
                meta = meta.copy(
                    recycleBinEnabled = true,
                    recycleBinUuid = recycleBin.uuid,
                    recycleBinChanged = Clock.System.now()
                )
            )
        }.modifyParentGroup {
            copy(groups = groups + recycleBin)
        }.run {
            block(recycleBin.uuid)
        }
    }
}

/**
 * Drops outdated history entries while taking into account `historyMaxItems`.
 * Note that `historyMaxSize` value is ignored by this method.
 *
 * @return modified [KeePassDatabase].
 */
fun KeePassDatabase.cleanupHistory(reference: Instant = Clock.System.now()): KeePassDatabase {
    val maintenancePeriod = content.meta.maintenanceHistoryDays.toInt().days

    return modifyContent {
        copy(
            group = group.cleanupChildHistory(
                reference = reference,
                maintenancePeriod = maintenancePeriod,
                historyMaxItems = content.meta.historyMaxItems
            )
        )
    }
}

private fun Group.cleanupChildHistory(
    reference: Instant,
    maintenancePeriod: Duration,
    historyMaxItems: Int
): Group = copy(
    groups = groups.map { group ->
        group.cleanupChildHistory(reference, maintenancePeriod, historyMaxItems)
    },
    entries = entries.map { entry ->
        entry.cleanupHistory(reference, maintenancePeriod, historyMaxItems)
    }
)

private fun Entry.cleanupHistory(
    reference: Instant,
    maintenancePeriod: Duration,
    historyMaxItems: Int
): Entry {
    val newHistory = history.filter { historicEntry ->
        historicEntry
            .times
            ?.lastModificationTime
            ?.let { lastModificationTime ->
                val period = reference - lastModificationTime
                period < maintenancePeriod
            }
            ?: true
    }

    return copy(
        history = when {
            historyMaxItems >= 0 -> newHistory.takeLast(historyMaxItems)
            else -> newHistory
        }
    )
}
