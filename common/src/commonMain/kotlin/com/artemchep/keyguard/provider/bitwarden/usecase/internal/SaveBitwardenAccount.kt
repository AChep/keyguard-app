package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.premium
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.model.Login
import com.artemchep.keyguard.provider.bitwarden.model.PasswordResult

fun interface SaveBitwardenAccount {
    /**
     * Saves the token produced by [login]. The premium check
     * runs before [login], so a refused user does not log in first.
     */
    operator fun invoke(login: IO<BitwardenToken>): IO<AccountId>
}

class SaveBitwardenAccountImpl(
    private val getPurchased: GetPurchased,
    private val getAccounts: GetAccounts,
    private val queueSyncById: QueueSyncById,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val logRepository: LogRepository,
    private val db: VaultDatabaseManager,
    private val markBackupAsDirty: MarkBackupAsDirty,
) : SaveBitwardenAccount {
    companion object {
        private const val TAG = "AddAccount.bitwarden"
    }

    override operator fun invoke(login: IO<BitwardenToken>): IO<AccountId> = ioEffect {
        val token = login.bind()
        db.mutate(TAG) { database ->
            database.accountQueries.insert(accountId = token.id, data = token)
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
            markBackupAsDirty()
                .bind()
        }
        // Log the time spent adding an account.
        .measure { duration, _ ->
            val message = "Added an account in $duration."
            logRepository.post(TAG, message)
        }
}

internal fun Base64Service.bitwardenToken(
    id: String,
    secrets: PasswordResult,
    login: Login,
    email: String,
    env: ServerEnv,
    sso: BitwardenToken.Sso? = null,
) = BitwardenToken(
    id = id,
    key = BitwardenToken.Key(
        masterKeyBase64 = encodeToString(secrets.masterKey),
        passwordKeyBase64 = encodeToString(secrets.passwordKey),
        encryptionKeyBase64 = encodeToString(secrets.encryptionKey),
        macKeyBase64 = encodeToString(secrets.macKey),
    ),
    token = BitwardenToken.Token(
        refreshToken = login.refreshToken,
        accessToken = login.accessToken,
        expirationDate = login.accessTokenExpiryDate,
    ),
    user = BitwardenToken.User(
        email = email,
    ),
    env = BitwardenToken.Environment.of(
        model = env,
    ),
    sso = sso,
)
