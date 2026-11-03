package com.artemchep.keyguard.common.service.gpgagent

/**
 * The components of this secret that the agent may use for [operation]. KEYINFO rows,
 * the persisted catalog and request routing all derive their capabilities from this.
 */
fun GpgAgentSecret.agentKeysFor(
    operation: GpgAgentOperation,
): List<GpgAgentKeyMetadataKey> {
    if (!hasPrivateKey) return emptyList()
    return when (operation) {
        // Signing new data needs a current, unrevoked authorization.
        GpgAgentOperation.SIGN -> authorizedAgentKeys.filter { it.canSign }
        // Historical decryption must keep finding exact secret
        // components after expiration or revocation.
        GpgAgentOperation.DECRYPT -> metadata.routableAgentKeys.filter { it.canDecrypt }
    }
}

private val sourceOrder = compareBy<GpgAgentKeyInfoRow>({ it.accountId }, { it.cipherId })

/** Prefers a copy that holds private material, then the stable source order. */
private val representativeOrder = compareBy<GpgAgentKeyInfoRow> { !it.canSign && !it.canDecrypt }
    .then(sourceOrder)

/**
 * Discovery describes the operations available across all copies of a component.
 * Keep the per-item rows intact so each request can select its own source.
 */
internal fun List<GpgAgentKeyInfoRow>.toGpgAgentKeys(): List<GpgAgentMessages.GpgKey> =
    groupBy { it.keygrip.normalizeGpgKeygrip() }
        .entries
        .sortedBy { it.key }
        .map { (keygrip, candidates) ->
            val representative = candidates.minWith(representativeOrder)
            GpgAgentMessages.GpgKey(
                name = representative.displayName,
                keygrip = keygrip,
                fingerprint = representative.fingerprint,
                algorithm = representative.algorithm,
                canSign = candidates.any { it.canSign },
                canDecrypt = candidates.any { it.canDecrypt },
            )
        }

/** Selects the item that serves the requested operation, whether the vault is locked or not. */
internal fun List<GpgAgentKeyInfoRow>.selectGpgAgentKeySource(
    operation: GpgAgentOperation,
): GpgAgentKeyInfoRow? = asSequence()
    .filter { it.supports(operation) }
    .minWithOrNull(sourceOrder)
