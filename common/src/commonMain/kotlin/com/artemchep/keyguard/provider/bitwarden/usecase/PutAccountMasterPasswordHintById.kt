package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.PutAccountMasterPasswordHintById
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.masterPasswordHint
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.profile
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileRequestEntity
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PutAccountMasterPasswordHintByIdImpl(
    private val logRepository: LogRepository,
    private val tokenRepository: BitwardenTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val base64Service: Base64Service,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: DatabaseManager,
) : PutAccountMasterPasswordHintById {
    companion object {
        private const val TAG = "PutAccountMasterPasswordHintById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        tokenRepository = directDI.instance(),
        profileRepository = directDI.instance(),
        base64Service = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
    )

    override fun invoke(
        request: Map<AccountId, String?>,
    ): IO<Unit> = request
        .entries
        .map { entry ->
            putAccountNameIo(
                accountId = entry.key,
                masterPasswordHint = entry.value,
            )
        }
        .parallel(Dispatchers.Default)
        .map {
            // Do not return the result.
        }

    private fun putAccountNameIo(
        accountId: AccountId,
        masterPasswordHint: String?,
    ) = tokenRepository
        .getById(accountId)
        .effectMap { user ->
            requireNotNull(user) {
                "Failed to find account tokens!"
            }

            val profile = profileRepository
                .getById(accountId)
                .first()!!
            val request = ProfileRequestEntity(
                culture = profile.culture.takeIf { it.isNotBlank() },
                name = profile.name.takeIf { it.isNotBlank() },
                masterPasswordHint = masterPasswordHint,
            )
            withRefreshableAccessToken(
                base64Service = base64Service,
                httpClient = httpClient,
                json = json,
                db = db,
                user = user,
            ) { latestUser ->
                val serverEnv = latestUser.env.back()
                val accessToken = requireNotNull(latestUser.token).accessToken
                serverEnv.api.accounts.profile(
                    httpClient = httpClient,
                    env = serverEnv,
                    token = accessToken,
                    model = request,
                )
            }

            // TODO: Instead of using cached profile model, use the one returned
            //  from the avatar update call.
            val newProfile =
                BitwardenProfile.masterPasswordHint.set(profile, masterPasswordHint.orEmpty())
            profileRepository.put(newProfile)
                .bind()
        }
}
