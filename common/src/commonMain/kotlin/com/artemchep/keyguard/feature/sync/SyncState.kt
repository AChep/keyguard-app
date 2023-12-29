package com.artemchep.keyguard.feature.sync

import com.artemchep.keyguard.ui.ContextItem
import kotlinx.coroutines.flow.StateFlow

data class SyncState(
    val itemsFlow: StateFlow<List<Item>>,
) {
    data class Item(
        val key: String,
        val email: String,
        val host: String,
        val status: Status,
        val items: List<ContextItem>,
        val lastSyncTimestamp: String?,
        val onClick: (() -> Unit)?,
    ) {
        sealed interface Status {
            data object Ok : Status

            data class Pending(
                val text: String,
            ) : Status

            data object Failed : Status
        }
    }
}
