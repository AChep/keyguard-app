package com.artemchep.keyguard.common.model

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
}
