package com.artemchep.keyguard.common.service.icon

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildIconsRequestUrl
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** The icon servers of the accounts of the unlocked vault. */
interface AccountIconServers {
    /**
     * Returns the icon server of the account, or `null` if there is no
     * such account or the account's client can not be created.
     */
    suspend fun get(accountId: AccountId): AccountIconServer?
}

sealed interface AccountIconServer {
    /**
     * The account's own icon server, reached with the account's
     * HTTP client, so it presents the account's client certificate.
     */
    class Remote(
        val env: ServerEnv,
        val httpClient: HttpClient,
    ) : AccountIconServer {
        fun requestUrl(host: String): String = env.buildIconsRequestUrl(host)
    }

    /** The account has no icon server; icons come from the websites. */
    data object Website : AccountIconServer
}

class AccountIconServersImpl(
    tokenRepository: ServiceTokenRepository,
    private val httpClientProvider: BitwardenHttpClientProvider,
    scope: CoroutineScope,
) : AccountIconServers {
    // Resolved once when the accounts change. The
    // clients come from the provider, which creates and caches them on
    // first use, so an account without visible icons never creates one.
    private val envs = tokenRepository.get()
        .map { tokens ->
            tokens.associateBy(
                keySelector = ServiceToken::id,
                valueTransform = ServiceToken::iconServerEnvOrNull,
            )
        }
        .shareIn(scope, SharingStarted.Lazily, replay = 1)

    override suspend fun get(accountId: AccountId): AccountIconServer? {
        val envs = envs.first()
        val env = envs[accountId.id]
        return when {
            accountId.id !in envs -> null
            env == null -> AccountIconServer.Website
            // An account whose client can not be created maps to
            // no server, so its icons do not leak to the websites.
            else -> runCatching { httpClientProvider.client(env) }
                .getOrNull()
                ?.let { httpClient ->
                    AccountIconServer.Remote(
                        env = env,
                        httpClient = httpClient,
                    )
                }
        }
    }
}

private fun ServiceToken.iconServerEnvOrNull(): ServerEnv? = when (this) {
    is BitwardenToken -> env.back()
    is KeePassToken -> null
}
