package com.artemchep.keyguard.provider.bitwarden.usecase

import androidx.compose.ui.graphics.Color
import app.keemobile.kotpass.database.modifiers.modifyMeta
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.exception.KeePassDatabaseModifiedExternallyException
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.keepass.getKeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.saveKeePassDatabase
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.PutAccountColorById
import com.artemchep.keyguard.common.util.toHex
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
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class PutAccountColorByIdImpl internal constructor(
    logRepository: LogRepository,
    tokenRepository: ServiceTokenRepository,
    profileRepository: BitwardenProfileRepository,
    putBitwardenAccountColorById: PutBitwardenAccountColorByIdImpl,
    putKeePassAccountColorById: PutKeePassAccountColorById,
) : PutAccountColorById {
    companion object {
        private const val TAG = "PutAccountColor"
    }

    private val putAccountColorById = PutAccountSettingById<Color>(
        logRepository = logRepository,
        tokenRepository = tokenRepository,
        profileRepository = profileRepository,
        tag = TAG,
        changeLogSubject = "account color",
        putBitwarden = { color, token, profile ->
            putBitwardenAccountColorById(
                color = color,
                token = token,
                profile = profile,
            )
        },
        putKeePass = { color, token, profile ->
            putKeePassAccountColorById(
                color = color,
                token = token,
                profile = profile,
            )
        },
    )

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        tokenRepository = directDI.instance(),
        profileRepository = directDI.instance(),
        putBitwardenAccountColorById = directDI.instance(),
        putKeePassAccountColorById = directDI.instance(),
    )

    override fun invoke(
        request: Map<AccountId, Color>,
    ): IO<Unit> = putAccountColorById(request)
}

internal class PutBitwardenAccountColorByIdImpl(
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
}

internal interface PutKeePassAccountColorById {
    operator fun invoke(
        color: Color,
        token: KeePassToken,
        profile: BitwardenProfile,
    ): IO<Unit>
}

internal class PutKeePassAccountColorByIdImpl(
    directDI: DirectDI,
) : PutKeePassAccountColorById {
    private val profileRepository: BitwardenProfileRepository = directDI.instance()
    private val base64Service: Base64Service = directDI.instance()
    private val fileService: FileService = directDI.instance()
    private val webDavClientFactory = KtorWebDavClientFactory(
        httpClient = directDI.instance(),
    )

    override operator fun invoke(
        color: Color,
        token: KeePassToken,
        profile: BitwardenProfile,
    ): IO<Unit> = ioEffect {
        val colorHexString = color.toHex()

        val metadataBefore = getKeePassDatabaseMetadata(
            fileService = fileService,
            token = token,
            webDavClientFactory = webDavClientFactory,
        )
        val curDatabase = openKeePassDatabase(
            token = token,
            fileService = fileService,
            base64Service = base64Service,
            webDavClientFactory = webDavClientFactory,
        )
        val newDatabase = curDatabase.modifyMeta {
            copy(
                color = colorHexString,
            )
        }
        val metadataAfter = getKeePassDatabaseMetadata(
            fileService = fileService,
            token = token,
            webDavClientFactory = webDavClientFactory,
        ).takeIf { candidate ->
            metadataBefore != null &&
                    candidate != null &&
                    metadataBefore.isComparableWith(candidate)
        }
        if (
            metadataBefore != null &&
            metadataAfter != null &&
            metadataBefore.differsFrom(metadataAfter)
        ) {
            throw KeePassDatabaseModifiedExternallyException(
                "KeePass database was modified externally while changing account color.",
            )
        }
        saveKeePassDatabase(
            fileService = fileService,
            token = token,
            database = newDatabase,
            base64Service = base64Service,
            webDavClientFactory = webDavClientFactory,
            expectedMetadata = metadataAfter,
        )

        val newProfile = BitwardenProfile.avatarColor.set(profile, colorHexString)
        profileRepository.put(newProfile)
            .bind()
    }
}
