package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassSyncCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

class SyncByKeePassTokenImpl(
    private val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val base32Service: Base32Service,
    private val base64Service: Base64Service,
    private val fileService: FileService,
    private val getPasswordStrength: GetPasswordStrength,
    private val json: Json,
    private val db: VaultDatabaseManager,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val webDavClientFactory: WebDavClientFactory,
    private val watchdog: Watchdog,
    private val gpgKeyMetadataResolver: GpgKeyMetadataResolver? = null,
) : SyncByKeePassToken {
    companion object {
        private const val TAG = "SyncById.keepass"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base32Service = directDI.instance(),
        base64Service = directDI.instance(),
        fileService = directDI.instance(),
        getPasswordStrength = directDI.instance(),
        gpgKeyMetadataResolver = directDI.instanceOrNull(),
        json = directDI.instance(),
        db = directDI.instance(),
        pendingUploadCoordinator = directDI.instance(),
        webDavClientFactory = KtorWebDavClientFactory(
            httpClient = directDI.instance(),
        ),
        watchdog = directDI.instance(),
    )

    override fun invoke(user: KeePassToken): IO<Unit> = watchdog
        .track(
            accountId = AccountId(user.id),
            accountTask = AccountTask.SYNC,
        ) {
            val coordinator = KeePassSyncCoordinator(
                logRepository = logRepository,
                cryptoGenerator = cryptoGenerator,
                base32Service = base32Service,
                base64Service = base64Service,
                fileService = fileService,
                getPasswordStrength = getPasswordStrength,
                gpgKeyMetadataResolver = gpgKeyMetadataResolver,
                json = json,
                db = db,
                pendingUploadCoordinator = pendingUploadCoordinator,
                webDavClientFactory = webDavClientFactory,
            )
            coordinator.sync(user)
        }
        .measure { duration, _ ->
            val message = "Synced KeePass account in $duration."
            logRepository.post(TAG, message)
        }
}
