package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildHost
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl
import com.artemchep.keyguard.provider.bitwarden.mapper.getHostName
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
class GetProfilesImpl(
    private val tokenRepository: ServiceTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetProfiles {
    companion object {
        private const val TAG = "GetProfiles"
    }

    constructor(directDI: DirectDI) : this(
        tokenRepository = directDI.instance(),
        profileRepository = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val sharedFlow = combine(
        tokenRepository.get(),
        profileRepository.get(),
    ) { tokens, profiles ->
        profiles
            .mapNotNull { profile ->
                val token = tokens.firstOrNull { it.id == profile.accountId }
                    ?: return@mapNotNull null
                when (token) {
                    is BitwardenToken -> {
                        val env = token.env.back()
                        profile.toDomain(
                            accountHost = env.buildHost(),
                        )
                    }
                    is KeePassToken -> {
                        val host = token.getHostName()
                        profile.toDomain(
                            accountHost = host,
                        )
                    }
                }
            }
            .sorted()
    }
        .flowOn(dispatcher)
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5000L), replay = 1)

    override fun invoke(): Flow<List<DProfile>> = sharedFlow
}
