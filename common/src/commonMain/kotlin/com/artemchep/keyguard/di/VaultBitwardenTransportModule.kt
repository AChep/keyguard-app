package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.icon.AccountIconServers
import com.artemchep.keyguard.common.service.icon.AccountIconServersImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientProvider
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientProviderImpl
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsService
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsServiceNative
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfaImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountMutualTls
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.plugin.module.dsl.scoped

internal class VaultBitwardenTransportModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<PutAccountMutualTls>()
            scoped<RequestEmailTfaImpl>() bind RequestEmailTfa::class
            scoped<BitwardenHttpClientProvider> {
                val provider = BitwardenHttpClientProviderImpl(httpClient = get(), factory = get())
                // A replaced or removed client certificate must not stay in
                // memory until the vault locks. The accounts are read in the
                // background, so creating the transport does not depend on the
                // database; if they can not be read, the clients stay until the lock.
                flow { emitAll(get<ServiceTokenRepository>().get()) }
                    .onEach { tokens ->
                        val envs = tokens.mapNotNull { token -> (token as? BitwardenToken)?.env?.back() }
                        provider.updateAccounts(envs)
                    }
                    .catch { }
                    .launchIn(requireNotNull(getSource<VaultScopeSource>()).coroutineScope)
                provider
            } onClose { it?.close() }
            scoped<MutualTlsServiceNative>() bind MutualTlsService::class
            scoped<AccountIconServers> {
                AccountIconServersImpl(
                    tokenRepository = get(),
                    httpClientProvider = get(),
                    scope = requireNotNull(getSource<VaultScopeSource>()).coroutineScope,
                )
            }
        }
    }
}
