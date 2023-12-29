package com.artemchep.keyguard.provider.bitwarden.usecase

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.PutAccountColorById
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.avatarColor
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.avatar
import com.artemchep.keyguard.provider.bitwarden.entity.AvatarRequestEntity
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PutAccountColorByIdImpl(
    private val logRepository: LogRepository,
    private val tokenRepository: BitwardenTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val base64Service: Base64Service,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: DatabaseManager,
) : PutAccountColorById {
    companion object {
        private const val TAG = "PutAccountColor.bitwarden"
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
        request: Map<AccountId, Color>,
    ): IO<Unit> = request
        .entries
        .map { entry ->
            putAccountColorIo(
                accountId = entry.key,
                color = entry.value,
            )
        }
        .parallel(Dispatchers.Default)
        .map {
            // Do not return the result.
        }

    private fun putAccountColorIo(
        accountId: AccountId,
        color: Color,
    ) = tokenRepository
        .getById(accountId)
        .effectMap { user ->
            requireNotNull(user) {
                "Failed to find account tokens!"
            }

            val colorString = color.toHex()
            val request = AvatarRequestEntity(
                avatarColor = colorString,
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
                serverEnv.api.accounts.avatar(
                    httpClient = httpClient,
                    env = serverEnv,
                    token = accessToken,
                    model = request,
                )
            }

            // TODO: Instead of using cached profile model, use the one returned
            //  from the avatar update call.
            profileRepository
                .getById(accountId)
                .toIO()
                .flatMap { profileOrNull ->
                    val currentProfile = profileOrNull
                        ?: return@flatMap ioUnit()
                    val newProfile = BitwardenProfile.avatarColor.set(currentProfile, colorString)
                    profileRepository.put(newProfile)
                }
                .bind()
        }
}
