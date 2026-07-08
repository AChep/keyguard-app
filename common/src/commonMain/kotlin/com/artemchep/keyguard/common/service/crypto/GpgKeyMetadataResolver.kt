package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata

interface GpgKeyMetadataResolver {
    fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
    ): GpgAgentKeyMetadata?
}

object GpgKeyMetadataResolverUnsupported : GpgKeyMetadataResolver {
    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
    ): GpgAgentKeyMetadata? = null
}
