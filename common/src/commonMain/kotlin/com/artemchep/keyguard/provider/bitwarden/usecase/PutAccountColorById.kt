package com.artemchep.keyguard.provider.bitwarden.usecase

import androidx.compose.ui.graphics.Color
import app.keemobile.kotpass.database.modifiers.modifyMeta
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.combineIo
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.saveKeePassDatabase
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.PutAccountColorById
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.avatarColor
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.avatar
import com.artemchep.keyguard.provider.bitwarden.entity.AvatarRequestEntity
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
class PutAccountColorByIdImpl(
    private val logRepository: LogRepository,
    private val tokenRepository: ServiceTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val base64Service: Base64Service,
    private val fileService: FileService,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: DatabaseManager,
) : PutAccountColorById {
    companion object {
        private const val TAG = "PutAccountColor"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        tokenRepository = directDI.instance(),
        profileRepository = directDI.instance(),
        base64Service = directDI.instance(),
        fileService = directDI.instance(),
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
                color = color,
                token = token,
                profile = profile,
            )

            is KeePassToken -> submitChangeIo(
                color = color,
                token = token,
                profile = profile,
            )
        }
            .measure { duration, _ ->
                val msg = "Submitted the account color change to remote in $duration."
                logRepository.post(
                    tag = TAG,
                    message = msg,
                )
            }
            .bind()
    }

    private fun submitChangeIo(
        color: Color,
        token: BitwardenToken,
        profile: BitwardenProfile,
    ) = ioEffect {
        val colorHexString = color.toHex()

        val request = AvatarRequestEntity(
            avatarColor = colorHexString,
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
            serverEnv.api.accounts.avatar(
                httpClient = httpClient,
                env = serverEnv,
                token = accessToken,
                model = request,
            )
        }

        // TODO: Instead of using cached profile model, use the one returned
        //  from the avatar update call.
        val newProfile = BitwardenProfile.avatarColor.set(profile, colorHexString)
        profileRepository.put(newProfile)
            .bind()
    }

    private fun submitChangeIo(
        color: Color,
        token: KeePassToken,
        profile: BitwardenProfile,
    ) = ioEffect {
        val colorHexString = color.toHex()

        val curDatabase = openKeePassDatabase(
            token = token,
            fileService = fileService,
            base64Service = base64Service,
        )
        val newDatabase = curDatabase.modifyMeta {
            copy(
                color = colorHexString,
            )
        }
        saveKeePassDatabase(
            fileService = fileService,
            token = token,
            database = newDatabase,
        )

        // TODO: Instead of using cached profile model, use the one returned
        //  from the avatar update call.
        val newProfile = BitwardenProfile.avatarColor.set(profile, colorHexString)
        profileRepository.put(newProfile)
            .bind()
    }
}
