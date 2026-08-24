package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution

interface GpgKeyMetadataResolver {
    /**
     * Resolves agent metadata for every ring containing [fingerprint]. Unlike primary-key UI
     * selection, the fingerprint may identify a primary key or a component because agent requests
     * are component-oriented. A null or blank fingerprint resolves all parseable input rings.
     * [candidateRevocationKeys] supplies external public keys that may authenticate designated
     * revocations; malformed candidates are ignored independently.
     */
    fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
    ): GpgAgentMetadataResolution?
}

/**
 * Resolves the metadata of a public key downloaded from a keyserver. A
 * downloaded key must yield metadata before it may be stored, so a failed
 * resolution throws instead of returning a partial record.
 */
fun GpgKeyMetadataResolver.resolveDownloadedGpgKeyMetadata(
    publicKeyArmored: String,
    fingerprint: String,
    privateKeyArmored: String? = null,
): GpgAgentKeyMetadata = resolve(
    privateKeyArmored = privateKeyArmored,
    publicKeyArmored = publicKeyArmored,
    fingerprint = fingerprint,
)?.metadata
    ?: throw IllegalStateException("Could not resolve downloaded GPG key metadata.")

object GpgKeyMetadataResolverUnsupported : GpgKeyMetadataResolver {
    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgAgentMetadataResolution? = null
}
