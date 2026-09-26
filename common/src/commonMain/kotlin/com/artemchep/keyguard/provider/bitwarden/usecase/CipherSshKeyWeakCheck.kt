package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.usecase.CipherSshKeyWeakCheck

class CipherSshKeyWeakCheckImpl(
    private val keyPairGenerator: KeyPairGenerator,
) : CipherSshKeyWeakCheck {
    companion object {
        const val RSA_MIN_LENGTH_BITS = 2048
    }

    override fun invoke(secret: DSecret): Boolean {
        if (secret.type != DSecret.Type.SshKey) {
            return false
        }

        val sshKey = secret.sshKey ?: return false
        val privateKey = sshKey.privateKey
            ?.takeUnless { it.isBlank() }
            ?: return false

        val length = keyPairGenerator.getPrivateKeyLengthOrNull(privateKey)
            ?: return false
        return length < RSA_MIN_LENGTH_BITS
    }
}
