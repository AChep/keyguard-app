package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.displayName
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Follows the vault session: publishes the [catalog] of its environments,
 * and revokes the access whenever the session or the dependencies of an
 * environment change. Runs from the creation of the repository, which
 * every reader of the catalog and every caller of the agent go through.
 */
class EnvironmentSessionTracker(
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: EnvironmentSessionAccess,
    private val access: EnvironmentAccessImpl,
    private val mounts: EnvironmentMountsImpl,
    scope: CoroutineScope,
) {
    private val mutableCatalog = MutableStateFlow(EnvironmentCatalog())
    val catalog: StateFlow<EnvironmentCatalog> = mutableCatalog.asStateFlow()

    init {
        scope.launch { track() }
    }

    private suspend fun track() {
        var previousSessionId: String? = null
        getVaultSession().collectLatest { session ->
            // A reader that requested access while locked is waiting for this
            // unlock. Keep its FIFO generation, while still revoking all grants.
            // Retiring or replacing an unlocked session always cancels delivery.
            val unlocking = previousSessionId == null && session is MasterSession.Key
            access.revoke(if (unlocking) EnvironmentRevocation.Unlocked else EnvironmentRevocation.SessionEnded)
            previousSessionId = (session as? MasterSession.Key)?.session?.id
            mutableCatalog.value = EnvironmentCatalog()
            val key = session as? MasterSession.Key ?: return@collectLatest
            val dependencies = sessionAccess(key) ?: return@collectLatest
            track(key, dependencies)
        }
    }

    private suspend fun track(key: MasterSession.Key, dependencies: EnvironmentSessionDependencies) {
        // Scope the previous snapshot to this unlocked session, so it is released on lock.
        var previousDelivery: Map<String, EnvironmentDeliverySnapshot>? = null
        combine(
            dependencies.getCiphers(), dependencies.getProfiles(), key.session.active,
        ) { ciphers, profiles, active -> Triple(ciphers, profiles, active) }
            .combine(dependencies.getCollections()) { snapshot, collections -> snapshot to collections }
            .collect { (snapshot, collections) ->
                val (ciphers, profiles, active) = snapshot
                if (!active) {
                    access.revoke(EnvironmentRevocation.SessionEnded)
                    previousDelivery = null
                    mutableCatalog.value = EnvironmentCatalog()
                    return@collect
                }
                val environmentSecrets = ciphers.filter {
                    it.type == DSecret.Type.Environment && it.deletedDate == null
                }
                val served = mounts.servedSnapshot(catalog.value)
                // Referenced items are delivered too: a change of their values
                // or permissions must reach the local .env files.
                val delivery = environmentDeliverySnapshots(environmentSecrets, ciphers, collections)
                val previous = previousDelivery
                if (previous != null && previous != delivery) {
                    // Access changes still revoke every caller grant, but only
                    // the environments with changed dependencies retire their FIFOs.
                    if (environmentPermissionsChanged(previous, delivery, ciphers, collections)) {
                        access.revoke(EnvironmentRevocation.PermissionsChanged)
                    }
                    access.retireDelivery(
                        available = delivery.keys,
                        changed = delivery.filter { (id, snapshot) -> previous[id] != snapshot }.keys,
                    )
                }
                previousDelivery = delivery
                val environments = environmentSecrets
                    .map { secret ->
                        val definition = (secret.environment as? EnvironmentPayload.Decoded)?.definition
                        EnvironmentSummary(
                            secret.id, secret.accountId, secret.name, definition?.variables?.size ?: 0,
                            secret.isEditable(environmentPermissions(secret, collections)),
                            remoteId = secret.service.remote?.id,
                        )
                    }
                mutableCatalog.value = EnvironmentCatalog(
                    accounts = profiles.map {
                        EnvironmentAccount(it.accountId, it.displayName, remoteId = it.profileId)
                    },
                    environments = environments,
                    unlocked = true,
                    sessionVersion = key.session.id,
                )
                access.refreshApprovals(key.session.id, environmentSecrets)
                mounts.publishServed(served, catalog.value)
            }
    }
}
