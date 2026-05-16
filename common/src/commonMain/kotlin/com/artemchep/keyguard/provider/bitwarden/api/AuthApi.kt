package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.exception.OutOfMemoryKdfException
import com.artemchep.keyguard.common.exception.isOutOfMemoryError
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.url
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.bodyOrApiException
import com.artemchep.keyguard.provider.bitwarden.api.builder.headers
import com.artemchep.keyguard.provider.bitwarden.api.builder.identity
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import com.artemchep.keyguard.provider.bitwarden.entity.AccountPreLoginRequest
import com.artemchep.keyguard.provider.bitwarden.entity.AccountPreLoginResponse
import com.artemchep.keyguard.provider.bitwarden.entity.ConnectTokenResponse
import com.artemchep.keyguard.provider.bitwarden.entity.TwoFactorProviderTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.toDomain
import com.artemchep.keyguard.provider.bitwarden.model.Login
import com.artemchep.keyguard.provider.bitwarden.model.PasswordResult
import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.utils.io.InternalAPI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PBKDF2_KEY_LENGTH = 32

suspend fun login(
    deviceIdUseCase: DeviceIdUseCase,
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    httpClient: HttpClient,
    json: Json,
    env: ServerEnv,
    twoFactorToken: ServerTwoFactorToken?,
    clientSecret: String?,
    email: String,
    password: String,
) = run {
    val deviceId = deviceIdUseCase().bind()
    val secrets = obtainSecrets(
        cryptoGenerator = cryptoGenerator,
        httpClient = httpClient,
        env = env,
        email = email,
        password = password,
    )
    val token = internalLogin(
        base64Service = base64Service,
        httpClient = httpClient,
        json = json,
        env = env,
        twoFactorToken = twoFactorToken,
        clientSecret = clientSecret,
        deviceId = deviceId,
        email = email,
        passwordKey = secrets.passwordKey,
    )
    secrets to token
}

@OptIn(InternalAPI::class)
private suspend fun internalLogin(
    base64Service: Base64Service,
    httpClient: HttpClient,
    json: Json,
    env: ServerEnv,
    twoFactorToken: ServerTwoFactorToken?,
    clientSecret: String?,
    deviceId: String,
    email: String,
    passwordKey: ByteArray,
): Login = httpClient
    .post(env.identity.connect.token) {
        val persona = CurrentPlatform
            .let(BitwardenPersona::of)

        headers(env)
        header("device-type", persona.deviceType)
        header("cache-control", "no-store")
        // Official Bitwarden backend specifically checks for this header,
        // which is just a base-64 string of an email.
        val emailBase64 = base64Service
            .encodeToString(email)
            .let(base64Service::url)
        header("Auth-Email", emailBase64)
        val passwordBase64 = base64Service
            .encodeToString(passwordKey)
        body = FormDataContent(
            Parameters.build {
                append("grant_type", "password")
                append("username", email)
                append("password", passwordBase64)
                append("scope", "api offline_access")
                append("client_id", persona.clientId)
                // As per
                // https://github.com/bitwarden/cli/issues/383#issuecomment-937819752
                // the backdoor to a captcha is a client secret.
                if (clientSecret != null) {
                    append("captchaResponse", clientSecret)
                }
                append("deviceIdentifier", deviceId)
                append("deviceType", persona.deviceType)
                append("deviceName", persona.deviceName)

                if (twoFactorToken?.provider == TwoFactorProviderType.EmailNewDevice) {
                    append("newDeviceOtp", twoFactorToken.token)
                } else if (twoFactorToken != null) {
                    val providerEntity = TwoFactorProviderTypeEntity.of(twoFactorToken.provider)
                    val providerId = json
                        .encodeToString(providerEntity)
                        .removeSurrounding("\"")
                    // None of the 2FA methods should use the leading / trailing
                    // whitespaces, so it's safe to trim them.
                    //
                    // https://github.com/AChep/keyguard-app/issues/99
                    val token = twoFactorToken.token.trim()
                    append("twoFactorProvider", providerId)
                    append("twoFactorToken", token)
                    append("twoFactorRemember", twoFactorToken.remember.int.toString())
                }
            },
        )

        attributes.put(routeAttribute, "get-token")
    }
    .bodyOrApiException<ConnectTokenResponse>()
    .toDomain()

@OptIn(InternalAPI::class)
suspend fun refresh(
    base64Service: Base64Service,
    httpClient: HttpClient,
    json: Json,
    env: ServerEnv,
    token: BitwardenToken.Token,
): Login = httpClient
    .post(env.identity.connect.token) {
        val persona = CurrentPlatform
            .let(BitwardenPersona::of)

        headers(env)
        header("device-type", persona.deviceType)
        header("cache-control", "no-store")
        val clientId = kotlin.runCatching {
            val jwtData = parseAccessTokenData(
                base64Service = base64Service,
                json = json,
                accessToken = token.accessToken,
            )
            jwtData["client_id"]?.jsonPrimitive?.content
        }.getOrNull()
            ?: persona.clientId
        body = FormDataContent(
            Parameters.build {
                append("grant_type", "refresh_token")
                append("client_id", clientId)
                append("refresh_token", token.refreshToken)
            },
        )

        attributes.put(routeAttribute, "refresh-token")
    }
    .bodyOrApiException<ConnectTokenResponse>()
    .toDomain()

private fun parseAccessTokenData(
    base64Service: Base64Service,
    json: Json,
    accessToken: String,
): JsonObject {
    val parts = accessToken.split(".")
    require(parts.size == 3) {
        "Access token must consist of 3 parts."
    }

    val dataJsonStringBase64 = parts[1]
    val dataJsonString = base64Service.decodeToString(dataJsonStringBase64)
    val dataJson = json.parseToJsonElement(dataJsonString) as JsonObject
    return dataJson
}

suspend fun obtainSecrets(
    cryptoGenerator: CryptoGenerator,
    httpClient: HttpClient,
    env: ServerEnv,
    email: String,
    password: String,
) = run {
    val prelogin = prelogin(
        httpClient = httpClient,
        env = env,
        email = email,
    )
    generateSecrets(
        cryptoGenerator,
        password,
        hashConfig = prelogin.hash,
        salt = prelogin.salt,
    )
}

private suspend fun prelogin(
    httpClient: HttpClient,
    env: ServerEnv,
    email: String,
): PreLogin {
    val normalizedEmail = normalizePreloginEmail(email)
    return try {
        preloginAt(
            httpClient = httpClient,
            env = env,
            email = normalizedEmail,
            url = env.identity.accounts.preloginPassword,
            route = "get-prelogin-password",
        )
    } catch (e: HttpException) {
        val fallbackStatusCodes = setOf(
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
        )
        if (e.statusCode !in fallbackStatusCodes) {
            throw e
        }

        preloginAt(
            httpClient = httpClient,
            env = env,
            email = normalizedEmail,
            url = env.identity.accounts.prelogin,
            route = "get-prelogin",
        )
    }
}

private fun normalizePreloginEmail(email: String) = email
    .trim()
    .lowercase()

private suspend fun preloginAt(
    httpClient: HttpClient,
    env: ServerEnv,
    email: String,
    url: String,
    route: String,
): PreLogin = httpClient
    .post(url) {
        headers(env)
        contentType(ContentType.Application.Json)
        setBody(
            AccountPreLoginRequest(
                email = email,
            ),
        )

        attributes.put(routeAttribute, route)
    }
    .bodyOrApiException<AccountPreLoginResponse>()
    .toDomain(email)

private fun generateSecrets(
    cryptoGenerator: CryptoGenerator,
    password: String,
    hashConfig: PreLogin.Hash,
    salt: String,
): PasswordResult {
    val passwordBytes = password.encodeToByteArray()
    val saltBytes = salt.encodeToByteArray()

    val masterKey = runCatching {
        cryptoGenerator.masterKeyHash(
            seed = passwordBytes,
            salt = saltBytes,
            config = hashConfig,
        )
    }.getOrElse { e ->
        if (e.isOutOfMemoryError()) {
            val newError = OutOfMemoryKdfException(
                m = e.message ?: "Out of memory",
                e = e,
            )
            throw newError
        }

        throw e
    }
    val passwordKey = cryptoGenerator.pbkdf2(
        seed = masterKey,
        salt = passwordBytes,
        iterations = 1,
        length = PBKDF2_KEY_LENGTH,
    )
    val (encryptionKey, macKey) = stretchMasterKey(
        cryptoGenerator = cryptoGenerator,
        key = masterKey,
    )
    return PasswordResult(
        masterKey = masterKey,
        passwordKey = passwordKey,
        encryptionKey = encryptionKey,
        macKey = macKey,
    )
}

private fun stretchMasterKey(
    cryptoGenerator: CryptoGenerator,
    key: ByteArray,
): Pair<ByteArray, ByteArray> {
    val encryptionKey = cryptoGenerator.hkdf(
        seed = key,
        info = "enc".encodeToByteArray(),
    )
    val macKey = cryptoGenerator.hkdf(
        seed = key,
        info = "mac".encodeToByteArray(),
    )
    return encryptionKey to macKey
}

private fun CryptoGenerator.masterKeyHash(
    seed: ByteArray,
    salt: ByteArray,
    config: PreLogin.Hash,
): ByteArray = when (config) {
    is PreLogin.Hash.Pbkdf2 ->
        pbkdf2(
            seed = seed,
            salt = salt,
            iterations = config.iterationsCount,
            length = PBKDF2_KEY_LENGTH,
        )

    is PreLogin.Hash.Argon2id -> {
        val memoryKb = 1024 * config.memoryMb
        val saltHash = hashSha256(salt)
        argon2(
            mode = Argon2Mode.ARGON2_ID,
            seed = seed,
            salt = saltHash,
            iterations = config.iterationsCount,
            memoryKb = memoryKb,
            parallelism = config.parallelism,
        )
    }
}
