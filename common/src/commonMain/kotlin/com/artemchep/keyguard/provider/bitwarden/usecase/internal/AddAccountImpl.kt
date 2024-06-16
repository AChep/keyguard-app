package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.premium
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.login
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class AddAccountImpl(
    private val getPurchased: GetPurchased,
    private val getAccounts: GetAccounts,
    private val queueSyncById: QueueSyncById,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val logRepository: LogRepository,
    private val deviceIdUseCase: DeviceIdUseCase,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val httpClient: HttpClient,
    private val json: Json,
    private val db: DatabaseManager,
) : AddAccount {
    companion object {
        private const val TAG = "AddAccount.bitwarden"
    }

    private val needsPremiumToAddAccountIo = getAccounts()
        .toIO()
        .map { accounts -> accounts.size > 1 }

    constructor(directDI: DirectDI) : this(
        getPurchased = directDI.instance(),
        getAccounts = directDI.instance(),
        queueSyncById = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
        logRepository = directDI.instance(),
        deviceIdUseCase = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        httpClient = directDI.instance(),
        json = directDI.instance(),
        db = directDI.instance(),
    )

    override fun invoke(
        accountId: String?,
        env: ServerEnv,
        twoFactorToken: ServerTwoFactorToken?,
        clientSecret: String?,
        email: String,
        password: String,
    ): IO<AccountId> = ioEffect(Dispatchers.Default) {
        val (cryptoSecrets, apiSecrets) = login(
            deviceIdUseCase = deviceIdUseCase,
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
            httpClient = httpClient,
            json = json,
            env = env,
            twoFactorToken = twoFactorToken,
            clientSecret = clientSecret?.takeUnless { it.isBlank() },
            email = email,
            password = password,
        )
        if (accountId != null) {
            // TODO: Validate that the old account matches a new one.
            //  This may save you from merging two unrelated ciphers together.
        }
        val token = BitwardenToken(
            id = accountId ?: cryptoGenerator.uuid(),
            key = BitwardenToken.Key(
                masterKeyBase64 = base64Service.encodeToString(cryptoSecrets.masterKey),
                passwordKeyBase64 = base64Service.encodeToString(cryptoSecrets.passwordKey),
                encryptionKeyBase64 = base64Service.encodeToString(cryptoSecrets.encryptionKey),
                macKeyBase64 = base64Service.encodeToString(cryptoSecrets.macKey),
            ),
            token = BitwardenToken.Token(
                refreshToken = apiSecrets.refreshToken,
                accessToken = apiSecrets.accessToken,
                expirationDate = apiSecrets.accessTokenExpiryDate,
            ),
            user = BitwardenToken.User(
                email = email,
            ),
            env = BitwardenToken.Environment.of(
                model = env,
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
            predicate = needsPremiumToAddAccountIo,
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
}
