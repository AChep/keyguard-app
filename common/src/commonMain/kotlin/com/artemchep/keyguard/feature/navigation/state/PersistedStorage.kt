package com.artemchep.keyguard.feature.navigation.state

sealed interface PersistedStorage {
    data object InMemory : PersistedStorage

    /**
     * Stores the latest value on the disk, allowing to
     * survive the restart of an app.
     */
    data class InDisk(
        val disk: DiskHandle,
    ) : PersistedStorage
}
