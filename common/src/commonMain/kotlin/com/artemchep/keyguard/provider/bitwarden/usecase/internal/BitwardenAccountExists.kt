package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.exception.AccountAlreadyExistsException
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository

/**
 * Fails if an account with the [email] is already added on the [env] server.
 * Two accounts of the same user would share a profile id, so the second
 * one would never get a profile.
 */
internal suspend fun BitwardenTokenRepository.requireNewAccount(
    email: String,
    env: ServerEnv,
) {
    val environment = BitwardenToken.Environment.of(env).normalize()
    val exists = getSnapshot().bind().any { token ->
        token.env.normalize() == environment && token.user.email.equals(email.trim(), ignoreCase = true)
    }
    if (exists) throw AccountAlreadyExistsException()
}

// A trailing slash or the letter case
// does not change the server.
private fun BitwardenToken.Environment.normalize() = copy(
    baseUrl = baseUrl.normalizeUrl(),
    webVaultUrl = webVaultUrl.normalizeUrl(),
    apiUrl = apiUrl.normalizeUrl(),
    identityUrl = identityUrl.normalizeUrl(),
    iconsUrl = iconsUrl.normalizeUrl(),
)

private fun String.normalizeUrl() = trim().trimEnd('/').lowercase()
