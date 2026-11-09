package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Which records of linked apps this device syncs, see [DAppLinkSyncCategory]. The switches
 * belong to this device and never sync. They live in the vault database, so they stay
 * when the app leaves the link.
 *
 * A switch key names a category, see [DAppLinkSyncCategory.key]. A later category may
 * switch single records, such as one preference, under `<category>/<record>`.
 */
class AppSyncSwitches(
    private val store: VaultSettingsKeyValueStore,
) {
    companion object {
        private const val KEY_PREFIX = "p2p.app.sync."
    }

    private fun preference(category: DAppLinkSyncCategory) =
        store.getBoolean(KEY_PREFIX + category.key, true)

    /** Emits whether this device syncs each category. */
    fun enabled(): Flow<Map<DAppLinkSyncCategory, Boolean>> = combine(
        DAppLinkSyncCategory.entries.map { category ->
            preference(category)
        },
    ) { values ->
        DAppLinkSyncCategory.entries
            .zip(values.toList())
            .toMap()
    }

    /** Returns the categories this device syncs now. */
    suspend fun enabledNow(): Set<DAppLinkSyncCategory> = enabled()
        .first()
        .filterValues { it }
        .keys

    /** Turns the sync of the [category] on this device on or off. */
    fun set(
        category: DAppLinkSyncCategory,
        enabled: Boolean,
    ): IO<Unit> = preference(category).setAndCommit(enabled)
}
