package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentOwnership
import kotlinx.coroutines.flow.StateFlow

/** The environments of the unlocked vault. */
interface EnvironmentRepository {
    val state: StateFlow<EnvironmentCatalog>

    suspend fun load(id: String): EnvironmentDocument

    /**
     * @param ownership The folder, organization and collections, `null` to keep
     * the stored ones. Only the folder of an existing environment can change.
     */
    suspend fun save(
        id: String?,
        accountId: String,
        name: String,
        definition: EnvironmentDefinition,
        description: String = "",
        ownership: EnvironmentOwnership? = null,
    ): String

    suspend fun rename(id: String, name: String)

    /** Also disables the local `.env` files of the environment. */
    suspend fun trash(id: String)
}

data class EnvironmentCatalog(
    val accounts: List<EnvironmentAccount> = emptyList(),
    val environments: List<EnvironmentSummary> = emptyList(),
    val unlocked: Boolean = false,
    /** The identity of the unlocked vault session, `null` while locked. */
    val sessionVersion: String? = null,
)

data class EnvironmentAccount(
    val id: String,
    val name: String,
    val remoteId: String? = null,
) {
    /** The ID that users, the CLI, and MCP see: the remote ID, or else the local one. */
    val externalId: String get() = remoteId ?: id
}

data class EnvironmentSummary(
    val id: String,
    val accountId: String,
    val name: String,
    val variableCount: Int,
    val editable: Boolean,
    val remoteId: String? = null,
) {
    /** The ID that users, the CLI, and MCP see: the remote ID, or else the local one. */
    val externalId: String get() = remoteId ?: id
}

data class EnvironmentDocument(
    val id: String,
    val accountId: String,
    val name: String,
    val description: String,
    val definition: EnvironmentDefinition?,
    val editable: Boolean,
    /** `true` if this version can not edit the [definition] without losing data. */
    val unsupported: Boolean,
    val ownership: EnvironmentOwnership = EnvironmentOwnership(),
)

/** Stable, non-secret error codes; raw provider exceptions never cross MCP. */
class EnvironmentServiceException(val code: String) : Exception(code)
