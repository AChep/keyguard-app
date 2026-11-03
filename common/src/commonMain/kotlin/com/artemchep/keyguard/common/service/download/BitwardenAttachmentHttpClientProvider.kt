package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.transport.BitwardenHttpClientProvider
import com.artemchep.keyguard.provider.bitwarden.transport.AccountHttpClient
import com.artemchep.keyguard.provider.bitwarden.transport.accountClientForUrl

fun interface BitwardenAttachmentHttpClientProvider {
    suspend operator fun invoke(localCipherId: String, url: String): AccountHttpClient
}

internal class BitwardenAttachmentHttpClientProviderImpl(
    private val tokenRepository: ServiceTokenRepository,
    private val cipherRepository: BitwardenCipherRepository,
    private val httpClientProvider: BitwardenHttpClientProvider,
) : BitwardenAttachmentHttpClientProvider {
    override suspend fun invoke(localCipherId: String, url: String): AccountHttpClient {
        val cipher = requireNotNull(cipherRepository.getById(localCipherId).bind())
        val token = tokenRepository.getById(AccountId(cipher.accountId)).bind() as? BitwardenToken
        return httpClientProvider.accountClientForUrl(requireNotNull(token).env.back(), url)
    }
}
