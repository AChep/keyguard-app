package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.runtime.MutableState
import com.artemchep.keyguard.platform.LeBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

interface RememberStateFlowScopeSub {
    suspend fun loadDiskHandle(key: String, global: Boolean = false): DiskHandle

    /**
     * Values persisted by the default overload must be bundle-compatible.
     * If [storage] uses disk, the value must also be JSON-safe.
     */
    fun <T> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage = PersistedStorage.InMemory,
        initialValue: () -> T,
    ): MutableStateFlow<T>

    /**
     * Use this overload for custom types by serializing
     * them to a bundle/JSON-safe value.
     */
    fun <T, S> mutablePersistedFlow(
        key: String,
        storage: PersistedStorage = PersistedStorage.InMemory,
        serialize: (Json, T) -> S,
        deserialize: (Json, S) -> T,
        initialValue: () -> T,
    ): MutableStateFlow<T>

    fun <T> asComposeState(
        key: String,
    ): MutableState<T>

    fun clearPersistedFlow(key: String)

    fun <T> mutableComposeState(
        sink: MutableStateFlow<T>,
    ): MutableState<T>

    fun persistedState(): LeBundle
}
