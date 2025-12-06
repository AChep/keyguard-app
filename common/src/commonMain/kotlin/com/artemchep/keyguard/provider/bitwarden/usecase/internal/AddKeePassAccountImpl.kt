package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.models.Meta
import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.premium
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.random.Random

class AddKeePassAccountImpl(
    private val getPurchased: GetPurchased,
    private val getAccounts: GetAccounts,
    private val queueSyncById: QueueSyncById,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val fileService: FileService,
    private val base64Service: Base64Service,
    private val db: VaultDatabaseManager,
) : AddKeePassAccount {
    companion object {
        private const val TAG = "AddAccount.keepass"
    }

    constructor(directDI: DirectDI) : this(
        getPurchased = directDI.instance(),
        getAccounts = directDI.instance(),
        queueSyncById = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
        logRepository = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        fileService = directDI.instance(),
        base64Service = directDI.instance(),
        db = directDI.instance(),
    )

    override fun invoke(
        params: AddKeePassAccountParams,
    ): IO<AccountId> = ioEffect(Dispatchers.Default) {
        when (params.mode) {
            is AddKeePassAccountParams.Mode.New -> {
                createKeepassDb(params)
            }

            is AddKeePassAccountParams.Mode.Open -> {
                // Do nothing.
            }
        }
        validateKeepassDbParams(params)

        val keyData = loadKeyData(params)
        val token = KeePassToken(
            id = cryptoGenerator.uuid(),
            key = KeePassToken.Key(
                passwordBase64 = params.password
                    .toByteArray()
                    .let(base64Service::encodeToString),
                keyBase64 = keyData?.let(base64Service::encodeToString),
            ),
            files = KeePassToken.Files(
                databaseUri = params.dbUri,
                databaseFileName = params.dbFileName,
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
        .effectTap { finalAccountId ->
            queueSyncById(finalAccountId)
                .launchIn(windowCoroutineScope)
        }
        // Log the time spent adding an account.
        .measure { duration, finalAccountId ->
            val message = "Added an account '$finalAccountId' in $duration."
            logRepository.post(TAG, message)
        }

    private suspend fun createKeepassDb(
        params: AddKeePassAccountParams,
    ) = withContext(Dispatchers.IO) {
        require(params.mode is AddKeePassAccountParams.Mode.New)
        // Check that the file doesn't exist before
        // replacing it with a new one if overwrite is
        // disallowed.
        if (!params.mode.allowOverwrite && fileService.exists(params.dbUri)) {
            throw KeePassFileAlreadyExistsException()
        }

        val credentials = loadCredentials(params)
        val database = KeePassDatabase.Ver4x.create(
            rootName = "Keyguard database", // name of the root group
            meta = Meta(
                name = "Keyguard database",
                color = kotlin.run {
                    val hue = Random.nextDouble(0.0, 360.0)
                        .toFloat()
                    val color = generateAccentColors(hue).dark
                    color.toHex()
                },
            ),
            credentials = credentials,
        )
        // Try to decode a database and check that it is actually real
        // and readable with the given credentials.
        fileService.writeToFile(params.dbUri)
            .use { outputStream ->
                database.encode(outputStream)
            }
    }

    private suspend fun validateKeepassDbParams(
        params: AddKeePassAccountParams,
    ) = withContext(Dispatchers.IO) {
        val credentials = loadCredentials(params)
        // Try to decode a database and check that it is actually real
        // and readable with the given credentials.
        val database = fileService.readFromFile(params.dbUri)
            .use { inputStream ->
                KeePassDatabase.decode(inputStream, credentials)
            }
        database.header.version.toString().isNotEmpty()
    }

    private suspend fun loadCredentials(
        params: AddKeePassAccountParams,
    ) = kotlin.run {
        val passphrase = EncryptedValue.fromString(text = params.password)
        val keyData = loadKeyData(params)
        if (keyData != null) {
            Credentials.from(passphrase, keyData)
        } else Credentials.from(passphrase)
    }

    private suspend fun loadKeyData(
        params: AddKeePassAccountParams,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val keyUri = params.keyUri
        // if there's no key URI then there's no key data
            ?: return@withContext null
        fileService.readFromFile(keyUri).use { inputStream ->
            inputStream.readBytes()
        }
    }
}
