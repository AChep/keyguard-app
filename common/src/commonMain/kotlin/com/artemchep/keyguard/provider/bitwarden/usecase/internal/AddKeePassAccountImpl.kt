package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.prepareKeePassDatabase
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.premium
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AddKeePassAccountImpl(
    private val getPurchased: GetPurchased,
    private val getAccounts: GetAccounts,
    private val queueSyncById: QueueSyncById,
    private val syncById: SyncById,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val fileService: FileService,
    private val base64Service: Base64Service,
    private val webDavClientFactory: WebDavClientFactory,
    private val db: VaultDatabaseManager,
) : AddKeePassAccount {
    companion object {
        private const val TAG = "AddAccount.keepass"
    }

    constructor(directDI: DirectDI) : this(
        getPurchased = directDI.instance(),
        getAccounts = directDI.instance(),
        queueSyncById = directDI.instance(),
        syncById = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
        logRepository = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        fileService = directDI.instance(),
        base64Service = directDI.instance(),
        webDavClientFactory = KtorWebDavClientFactory(
            httpClient = directDI.instance(),
        ),
        db = directDI.instance(),
    )

    override fun invoke(
        params: AddKeePassAccountParams,
    ): IO<AccountId> = ioEffect(Dispatchers.Default) {
        val preparedDatabase = prepareKeePassDatabase(
            fileService = fileService,
            params = params,
            webDavClientFactory = webDavClientFactory,
        )
        val token = KeePassToken(
            id = cryptoGenerator.uuid(),
            key = KeePassToken.Key(
                passwordBase64 = params.password
                    .encodeToByteArray()
                    .let(base64Service::encodeToString),
                keyBase64 = preparedDatabase.keyData
                    ?.let(base64Service::encodeToString),
            ),
            database = KeePassToken.Database(
                fileName = params.dbFileName,
                location = params.webDav?.let { webDav ->
                    val credentials = webDav.credentials
                    FileLocation.WebDav(
                        url = webDav.url,
                        username = credentials?.username,
                        password = credentials?.password
                            ?.takeIf { it.value.isNotEmpty() },
                        displayName = params.dbFileName,
                    )
                } ?: FileLocation.Local(
                    uri = params.dbUri,
                    accessToken = params.dbAccessToken,
                    managedByApp = params.managedByApp,
                    displayName = params.dbFileName,
                ),
            ),
        )

        db.mutate(TAG) { database ->
            database.accountQueries.insert(
                accountId = token.id,
                data = token,
            )
        }.bind()

        AccountId(token.id)
    }
        // Require premium for adding more than
        // one account.
        .premium(
            getPurchased = getPurchased,
            predicate = AddAccountUtils.getPremiumAddAccountPredicateIo(
                getAccounts = getAccounts,
            ),
        )
        .flatMap { finalAccountId ->
            when (params.syncMode) {
                AddKeePassAccountParams.SyncMode.Queued -> ioEffect {
                    queueSyncById(finalAccountId)
                        .launchIn(windowCoroutineScope)
                    finalAccountId
                }

                AddKeePassAccountParams.SyncMode.Direct -> syncById(finalAccountId)
                    .map { finalAccountId }
            }
        }
        // Log the time spent adding an account.
        .measure { duration, finalAccountId ->
            val message = "Added an account '$finalAccountId' in $duration."
            logRepository.post(TAG, message)
        }
}
