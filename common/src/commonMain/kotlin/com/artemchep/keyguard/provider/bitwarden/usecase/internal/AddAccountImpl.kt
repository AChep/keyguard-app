package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.login
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

/**
 * @author Artem Chepurnyi
 */
class AddAccountImpl(
    private val deviceIdUseCase: DeviceIdUseCase,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val httpClient: HttpClient,
    private val json: Json,
    private val saveAccount: SaveBitwardenAccount,
) : AddAccount {
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
        base64Service.bitwardenToken(
            id = accountId ?: cryptoGenerator.uuid(),
            secrets = cryptoSecrets,
            login = apiSecrets,
            email = email,
            env = env,
        )
    }.let(saveAccount::invoke)
}
