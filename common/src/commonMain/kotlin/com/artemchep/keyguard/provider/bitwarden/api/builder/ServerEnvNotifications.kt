package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.provider.bitwarden.ServerEnv

/**
 * A DSL for the Identity server endpoints. To use, call [ServerEnv.identity]
 * property.
 *
 * @author Artem Chepurnyi
 */
@JvmInline
value class ServerEnvIdentity @Deprecated("Use the [ServerEnv.identity] property instead.") constructor(
    private val url: String,
) {
    val connect get() = Connect(url = url + "connect/")

    @JvmInline
    value class Connect(
        private val url: String,
    ) {
        val token get() = url + "token"
    }
}

@Suppress("DEPRECATION")
val ServerEnv.identity
    get() = ServerEnvIdentity(url = buildIdentityUrl())
