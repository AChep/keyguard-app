package com.artemchep.keyguard.common.service.gpgagent

data class GpgAgentPublicKeyRow(
    val keygrip: String,
    val fingerprint: String,
    val algorithm: String,
    val canSign: Boolean,
    val canDecrypt: Boolean,
    val publicKeyArmored: String?,
    val name: String?,
) {
    val displayName: String
        get() = name
            ?.takeIf { it.isNotBlank() }
            ?: fingerprint.takeIf { it.isNotBlank() }
            ?: keygrip
}
