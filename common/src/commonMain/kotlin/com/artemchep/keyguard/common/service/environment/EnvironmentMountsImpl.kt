package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.keyvalue.getSerializable
import com.artemchep.keyguard.feature.environments.runnableDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The side of the local `.env` files that a platform runtime drives: it
 * describes the agent of the build and reports the state of each file.
 * Screens use [EnvironmentMounts] instead.
 */
interface EnvironmentMountsHost : EnvironmentMounts {
    /**
     * @param supported Whether the agent of this build serves local `.env` files.
     * @param requiresDirectoryGrant Whether the agent is sandboxed, so it
     *   opens each directory with a bookmark that the app hands over.
     */
    fun configure(supported: Boolean, requiresDirectoryGrant: Boolean = false)

    fun setStatus(id: String, status: EnvironmentMountStatus)

    /** Clears the statuses of the files while the agent is not [ready]. */
    fun onAgentStatus(ready: Boolean)

    /** Refreshes the directory grant of a mount, without a new mount or approval. */
    suspend fun refreshBookmark(id: String, path: String, bookmark: String)
}

class EnvironmentMountsImpl(
    private val vault: EnvironmentVault,
    private val access: EnvironmentAccessImpl,
    private val cryptoGenerator: CryptoGenerator,
    storeFactory: KeyValueStoreFactory,
    json: Json,
    scope: CoroutineScope,
) : EnvironmentMountsHost {
    private val preference = storeFactory.get(Files.SETTINGS).getSerializable<List<EnvironmentMount>>(
        json, "environments.mounts.v1", emptyList(),
    )

    // Local .env files are unsupported until a platform runtime configures them.
    private val mutableState = MutableStateFlow(EnvironmentMountsState())
    override val state = mutableState.asStateFlow()
    private val requiresDirectoryGrant = MutableStateFlow(false)

    /** Replaced whenever the served mounts change without a revocation. */
    private val localRevision = MutableStateFlow(cryptoGenerator.uuid())

    /**
     * An opaque revision of the mounts the agent serves. Every revocation
     * changes it as well, because it retires the readers of the files.
     */
    internal val revision: String
        get() = revisionOf(localRevision.value, access.generation.value)

    init {
        scope.launch {
            preference.collect { mounts ->
                val previous = mutableState.value.mounts
                mutableState.update { it.copy(mounts = mounts) }
                if (previous.map { it.copy(bookmark = null) } != mounts.map { it.copy(bookmark = null) }) {
                    access.revoke(EnvironmentRevocation.ConfigurationChanged)
                } else if (previous != mounts) {
                    // A refreshed directory grant.
                    localRevision.value = cryptoGenerator.uuid()
                }
            }
        }
    }

    /** Suspends until the [revision] differs from the [previous] one. */
    internal suspend fun awaitRevision(previous: String) {
        combine(localRevision, access.generation, ::revisionOf).first { it != previous }
    }

    override fun configure(supported: Boolean, requiresDirectoryGrant: Boolean) {
        this.requiresDirectoryGrant.value = requiresDirectoryGrant
        mutableState.update { it.copy(supported = supported) }
    }

    override fun onAgentStatus(ready: Boolean) {
        if (!ready) mutableState.update { it.copy(statuses = emptyMap()) }
    }

    override fun setStatus(id: String, status: EnvironmentMountStatus) {
        mutableState.update { state ->
            if (state.mounts.any { it.id == id && it.enabled }) {
                state.copy(statuses = state.statuses + (id to status))
            } else {
                state
            }
        }
    }

    /** The enabled mounts the agent serves: while unlocked, only those of an available environment. */
    internal fun served(catalog: EnvironmentCatalog): List<EnvironmentMount> {
        val availableIds = catalog.environments.map { it.id }.toSet()
        return state.value.mounts.filter { it.enabled && (!catalog.unlocked || it.environmentId in availableIds) }
    }

    internal fun servedSnapshot(catalog: EnvironmentCatalog): List<Pair<EnvironmentMount, String>> =
        served(catalog).map { it to access.deliveryGeneration(it.environmentId) }

    /** Wakes the agent's watch if the mounts it serves changed since [previous]. */
    internal fun publishServed(previous: List<Pair<EnvironmentMount, String>>, catalog: EnvironmentCatalog) {
        if (servedSnapshot(catalog) != previous) localRevision.value = cryptoGenerator.uuid()
    }

    override suspend fun create(
        environmentId: String,
        path: String,
        bookmark: String?,
    ): EnvironmentMount = vault.writeLock.withLock {
        if (!state.value.supported) throw EnvironmentServiceException("unsupported_platform")
        val runnable = vault.withVault { dependencies ->
            val secret = dependencies.environmentSecret(environmentId)
            access.checkReadAccess(secret, environmentPermissions(secret, dependencies.getCollections().first()))
            secret.environment.runnableDefinition() != null
        }
        if (!runnable) throw EnvironmentServiceException("unsupported_environment")
        // Every spelling of a path has one form, so equal paths conflict.
        val mountPath = normalizeEnvironmentMountPath(path)
            ?: throw EnvironmentServiceException("invalid_path")
        val mounts = preference.first()
        existingMount(mounts, environmentId, mountPath)?.let { return@withLock it }
        if (mounts.count { it.enabled } >= MAX_MOUNTS) throw EnvironmentServiceException("mount_limit")
        val grant = bookmark ?: access.directoryGrant(mountPath)
        if (requiresDirectoryGrant.value && grant == null) {
            throw EnvironmentServiceException("directory_access_required")
        }
        val mount = EnvironmentMount(cryptoGenerator.uuid(), environmentId, mountPath, bookmark = grant)
        checkEnvironmentOperation()
        val updated = mounts.filterNot { !it.enabled && it.path == mountPath } + mount
        preference.setAndCommit(updated).bind()
        mutableState.update { it.copy(mounts = updated) }
        access.revoke(EnvironmentRevocation.ConfigurationChanged)
        mount
    }

    override suspend fun refreshBookmark(id: String, path: String, bookmark: String) = vault.writeLock.withLock {
        val mounts = preference.first()
        val mount = mounts.firstOrNull { it.id == id && it.path == path && it.enabled } ?: return@withLock
        if (mount.bookmark == bookmark) return@withLock
        val updated = mounts.map { if (it.id == id) it.copy(bookmark = bookmark) else it }
        preference.setAndCommit(updated).bind()
        mutableState.update { it.copy(mounts = updated) }
        localRevision.value = cryptoGenerator.uuid()
    }

    override suspend fun disable(id: String) = vault.writeLock.withLock {
        checkEnvironmentOperation()
        val mounts = preference.first().map { if (it.id == id) it.copy(enabled = false) else it }
        preference.setAndCommit(mounts).bind()
        mutableState.update { it.copy(mounts = mounts, statuses = it.statuses - id) }
        access.revoke(EnvironmentRevocation.ConfigurationChanged)
    }

    /** Disables the local `.env` files of a trashed environment. */
    internal suspend fun disableAll(environmentId: String) {
        state.value.mounts.filter { it.environmentId == environmentId }.forEach { disable(it.id) }
    }
}

private const val MAX_MOUNTS = 10

private fun revisionOf(local: String, generation: String) = "$local.$generation"

private fun existingMount(
    mounts: List<EnvironmentMount>, environmentId: String, path: String,
): EnvironmentMount? = mounts.firstOrNull { it.enabled && it.path == path }?.also {
    if (it.environmentId != environmentId) throw EnvironmentServiceException("path_conflict")
}
