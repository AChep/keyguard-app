package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.combineIo
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.PutAccountMasterPasswordHintById
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.masterPasswordHint
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.profile
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileRequestEntity
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PutAccountMasterPasswordHintByIdImpl(
    private val logRepository: LogRepository,
    private val tokenRepository: ServiceTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val base64Service: Base64Service,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: VaultDatabaseManager,
) : PutAccountMasterPasswordHintById {
    companion object {
        private const val TAG = "PutAccountMasterPasswordHintById"
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
            putAccountPasswordHintIo(
                accountId = entry.key,
                passwordHint = entry.value,
            )
        }
        .parallel(Dispatchers.Default)
        .map {
            // Do not return the result.
        }

    private fun putAccountPasswordHintIo(
        accountId: AccountId,
        passwordHint: String?,
    ) = combineIo(
        tokenRepository
            .getById(accountId),
        profileRepository
            .getById(accountId)
            .toIO(),
    ) { token, profile ->
        requireNotNull(token) { "Failed to find the account tokens!" }
        requireNotNull(profile) { "Failed to find the account profile!" }

        when (token) {
            is BitwardenToken -> submitChangeIo(
                passwordHint = passwordHint,
                token = token,
                profile = profile,
            )

            is KeePassToken -> submitChangeIo(
                passwordHint = passwordHint,
                token = token,
                profile = profile,
            )
        }
            .measure { duration, _ ->
                val msg =
                    "Submitted the account master password hint change to remote in $duration."
                logRepository.post(
                    tag = TAG,
                    message = msg,
                )
            }
            .bind()
    }

    private fun submitChangeIo(
        passwordHint: String?,
        token: BitwardenToken,
        profile: BitwardenProfile,
    ) = ioEffect {
        val request = ProfileRequestEntity(
            culture = profile.culture.takeIf { it.isNotBlank() },
            name = profile.name.takeIf { it.isNotBlank() },
            masterPasswordHint = passwordHint,
        )
        withRefreshableAccessToken(
            base64Service = base64Service,
            httpClient = httpClient,
            json = json,
            db = db,
            user = token,
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
        val newProfile = BitwardenProfile.masterPasswordHint.set(profile, passwordHint)
        profileRepository.put(newProfile)
            .bind()
    }

    private fun submitChangeIo(
        passwordHint: String?,
        token: KeePassToken,
        profile: BitwardenProfile,
    ) = ioEffect {
        val msg = "Setting master password hint is not supported on the " +
                "KeePass database!"
        throw RuntimeException(msg)
    }
}
