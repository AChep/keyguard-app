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
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.PutAccountNameById
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.name
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
class PutAccountNameByIdImpl internal constructor(
    private val logRepository: LogRepository,
    private val tokenRepository: ServiceTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val putBitwardenAccountNameById: PutBitwardenAccountNameByIdImpl,
    private val putKeePassAccountNameById: PutKeePassAccountNameById,
) : PutAccountNameById {
    companion object {
        private const val TAG = "PutAccountNameById"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        tokenRepository = directDI.instance(),
        profileRepository = directDI.instance(),
        putBitwardenAccountNameById = directDI.instance(),
        putKeePassAccountNameById = directDI.instance(),
    )

    override fun invoke(
        request: Map<AccountId, String>,
    ): IO<Unit> = request
        .entries
        .map { entry ->
            putAccountNameIo(
                accountId = entry.key,
                accountName = entry.value,
            )
        }
        .parallel(Dispatchers.Default)
        .map {
            // Do not return the result.
        }

    private fun putAccountNameIo(
        accountId: AccountId,
        accountName: String,
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
            is BitwardenToken -> putBitwardenAccountNameById(
                accountName = accountName,
                token = token,
                profile = profile,
            )

            is KeePassToken -> putKeePassAccountNameById(
                accountName = accountName,
                token = token,
                profile = profile,
            )
        }
            .measure { duration, _ ->
                val msg = "Submitted the account name change to remote in $duration."
                logRepository.post(
                    tag = TAG,
                    message = msg,
                )
            }
            .bind()
    }
}

internal class PutBitwardenAccountNameByIdImpl(
    private val profileRepository: BitwardenProfileRepository,
    private val base64Service: Base64Service,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: VaultDatabaseManager,
) {
    constructor(directDI: DirectDI) : this(
        profileRepository = directDI.instance(),
        base64Service = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
    )

    operator fun invoke(
        accountName: String,
        token: BitwardenToken,
        profile: BitwardenProfile,
    ) = ioEffect {
        val request = ProfileRequestEntity(
            culture = profile.culture.takeIf { it.isNotBlank() },
            name = accountName,
            masterPasswordHint = profile.masterPasswordHint?.takeIf { it.isNotBlank() },
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
        val newProfile = BitwardenProfile.name.set(profile, accountName)
        profileRepository.put(newProfile)
            .bind()
    }
}

internal interface PutKeePassAccountNameById {
    operator fun invoke(
        accountName: String,
        token: KeePassToken,
        profile: BitwardenProfile,
    ): IO<Unit>
}

internal expect fun createPutKeePassAccountNameById(
    directDI: DirectDI,
): PutKeePassAccountNameById
