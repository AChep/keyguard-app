package com.artemchep.keyguard.common.service.sshagent

data class SshAgentPublicKeyRow(
    val accountId: String,
    val cipherId: String,
    val publicKeyBlobSha256: String,
    val publicKey: String,
    val keyType: String,
    val fingerprint: String,
    val canSign: Boolean,
    val name: String?,
) {
    val displayName: String
        get() = name
            ?.takeIf { it.isNotBlank() }
            ?: fingerprint.takeIf { it.isNotBlank() }
            ?: keyType
}

data class SshAgentPublicKeyMaterial(
    val publicKeyBlobSha256: String,
    val publicKey: String,
    val keyType: String,
)
