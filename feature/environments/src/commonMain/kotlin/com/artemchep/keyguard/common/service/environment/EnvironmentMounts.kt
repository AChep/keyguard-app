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

/**
 * Returns the one form of a local `.env` file [path], without `.` and empty
 * segments, or `null` if the agent would refuse it. The path must be absolute
 * and name a file. `..` is refused: resolving it without the file system
 * would ignore symlinks.
 */
fun normalizeEnvironmentMountPath(path: String): String? {
    val segments = path.split('/')
    // A path that ends with `/` or `/.` names a directory.
    val valid = path.startsWith('/') &&
        '\u0000' !in path &&
        ".." !in segments &&
        segments.last().let { it.isNotEmpty() && it != "." }
    return segments
        .filter { it.isNotEmpty() && it != "." }
        .joinToString(separator = "/", prefix = "/")
        .takeIf { valid }
}
