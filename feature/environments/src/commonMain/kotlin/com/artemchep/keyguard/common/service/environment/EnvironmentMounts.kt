package com.artemchep.keyguard.common.service.environment

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** The local `.env` files of this device. */
interface EnvironmentMounts {
    val state: StateFlow<EnvironmentMountsState>

    suspend fun create(environmentId: String, path: String, bookmark: String? = null): EnvironmentMount

    suspend fun disable(id: String)
}

data class EnvironmentMountsState(
    val mounts: List<EnvironmentMount> = emptyList(),
    /** Transient agent status, kept separate from the persisted mount configuration. */
    val statuses: Map<String, EnvironmentMountStatus> = emptyMap(),
    /** Whether the agent of this build serves local `.env` files. */
    val supported: Boolean = false,
)

enum class EnvironmentMountStatus(val key: String) {
    Pending("pending"),
    Ready("ready"),
    Error("error"),
}

/** Device-local configuration. Never contains environment names or values. */
@Serializable
data class EnvironmentMount(
    val id: String,
    val environmentId: String,
    val path: String,
    val enabled: Boolean = true,
    val bookmark: String? = null,
)
