package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.requestEmailCode
import com.artemchep.keyguard.provider.bitwarden.api.obtainSecrets
import com.artemchep.keyguard.provider.bitwarden.entity.TwoFactorEmailRequestEntity
import io.ktor.client.HttpClient
import org.kodein.di.DirectDI
import org.kodein.di.instance

interface RequestEmailTfa : (
    ServerEnv,
    String, // email
    String, // password
) -> IO<Unit>

class RequestEmailTfaImpl(
    private val deviceIdUseCase: DeviceIdUseCase,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val httpClient: HttpClient,
) : RequestEmailTfa {
    constructor(directDI: DirectDI) : this(
        deviceIdUseCase = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        httpClient = directDI.instance(),
    )

    override fun invoke(
        env: ServerEnv,
        email: String,
        password: String,
    ): IO<Unit> = ioEffect {
        val secrets = obtainSecrets(
            cryptoGenerator = cryptoGenerator,
            httpClient = httpClient,
            env = env,
            email = email,
            password = password,
        )
        val request = TwoFactorEmailRequestEntity(
            email = email,
            masterPasswordHash = base64Service.encodeToString(secrets.passwordKey),
            deviceIdentifier = deviceIdUseCase().bind(),
        )
        env.api.twoFactor.requestEmailCode(
            httpClient = httpClient,
            env = env,
            model = request,
        )
    }
}
