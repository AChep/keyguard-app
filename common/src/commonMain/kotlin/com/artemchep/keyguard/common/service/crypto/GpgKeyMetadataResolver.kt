package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata

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
    ): GpgAgentKeyMetadata?
}

object GpgKeyMetadataResolverUnsupported : GpgKeyMetadataResolver {
    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgAgentKeyMetadata? = null
}
