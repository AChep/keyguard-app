package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.gpgagent.isUsableAgentKey
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys

sealed interface GetPasswordResult {
    fun message(): String

    fun isValid(): Boolean

    data class Value(
        val value: String,
    ) : GetPasswordResult {
        override fun message(): String = value
        override fun isValid(): Boolean = value.isNotBlank()
    }

    data class AsyncKey(
        val keyPair: KeyPair,
    ) : GetPasswordResult {
        override fun message(): String = keyPair.publicKey.fingerprint
        override fun isValid(): Boolean = true
    }

    data class AsyncGpgKey(
        val gpgKey: GeneratedGpgKey,
    ) : GetPasswordResult {
        override fun message(): String = gpgKey.fingerprint
        override fun isValid(): Boolean =
            gpgKey.privateKeyArmored.isNotBlank() &&
                    gpgKey.publicKeyArmored.isNotBlank() &&
                    gpgKey.fingerprint.isNotBlank() &&
                    gpgKey.metadata?.routableAgentKeys?.any { it.isUsableAgentKey } == true
    }
}
