package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyMetadata
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType

internal fun NativeOpenPgpKeyMetadata.toDomain(): GpgAgentKeyMetadata = GpgAgentKeyMetadata(
    version = version,
    keys = keys.map { key ->
        GpgAgentKeyMetadataKey(
            keygrip = key.keygrip,
            fingerprint = key.fingerprint,
            algorithm = key.algorithm,
            capabilities = key.capabilities,
        )
    },
)

internal fun NativeSshKeyType.toDomain(): KeyPair.Type = when (this) {
    NativeSshKeyType.RSA -> KeyPair.Type.RSA
    NativeSshKeyType.ED25519 -> KeyPair.Type.ED25519
}
