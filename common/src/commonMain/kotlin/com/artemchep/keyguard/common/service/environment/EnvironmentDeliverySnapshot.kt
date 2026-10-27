package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret

/** Dependencies of one environment; keep this snapshot only within its unlocked session. */
internal data class EnvironmentDeliverySnapshot(
    val secrets: Map<String, DSecret>,
    val permissions: Map<String, EnvironmentPermissions>,
)

internal fun environmentDeliverySnapshots(
    environments: List<DSecret>,
    ciphers: List<DSecret>,
    collections: List<DCollection>,
): Map<String, EnvironmentDeliverySnapshot> {
    val references = environmentReferencedSecretsByEnvironment(environments, ciphers)
    return environments.associate { environment ->
        // Map equality ignores provider ordering, which must not restart readers.
        val secrets = (listOf(environment) + references[environment.id].orEmpty()).associateBy { it.id }
        environment.id to EnvironmentDeliverySnapshot(
            secrets = secrets,
            permissions = secrets.mapValues { (_, secret) -> environmentPermissions(secret, collections) },
        )
    }
}

internal fun environmentPermissionsChanged(
    previous: Map<String, EnvironmentDeliverySnapshot>,
    current: Map<String, EnvironmentDeliverySnapshot>,
    ciphers: List<DSecret>,
    collections: List<DCollection>,
): Boolean {
    if (previous.keys.any { it !in current }) return true
    val currentSecrets = ciphers.filter { it.deletedDate == null }.associateBy { it.id }
    // Ceasing to reference a live item is an ordinary definition edit. Only its
    // removal or changed access revokes already approved operations globally.
    return previous.values.any { snapshot ->
        snapshot.secrets.any { (id, secret) ->
            val latest = currentSecrets[id]?.takeIf { it.accountId == secret.accountId }
            latest == null || environmentPermissions(latest, collections) != snapshot.permissions.getValue(id)
        }
    }
}
