package com.artemchep.keyguard.feature.sync

sealed interface SyncItem {
    val key: String

    data class Section(
        override val key: String,
    ) : SyncItem

    data class Account(
        override val key: String,
        val title: String,
        val text: String,
        /**
         * Contains last sync status of the account,
         * `null` if the account never had the sync
         * performed yet.
         */
        val lastSyncStatus: LastSyncStatus?,
    ) : SyncItem {
        data class LastSyncStatus(
            val timestamp: String,
            val success: Boolean,
        )
    }
}
