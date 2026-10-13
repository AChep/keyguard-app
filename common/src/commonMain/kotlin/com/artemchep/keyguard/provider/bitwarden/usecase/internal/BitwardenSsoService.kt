package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.decodeOrNull
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.feature.auth.bitwarden.sso.SsoAuthorization
import com.artemchep.keyguard.feature.auth.bitwarden.sso.SsoException
import com.artemchep.keyguard.feature.auth.bitwarden.sso.SsoFailure
import com.artemchep.keyguard.feature.auth.bitwarden.sso.SsoRequest
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.bodyOrApiException
import com.artemchep.keyguard.provider.bitwarden.api.builder.headers
import com.artemchep.keyguard.provider.bitwarden.api.builder.requestEmailCode
import com.artemchep.keyguard.provider.bitwarden.api.builder.routeAttribute
import com.artemchep.keyguard.provider.bitwarden.api.generateKeyConnectorSecrets
import com.artemchep.keyguard.provider.bitwarden.api.generateSecrets
import com.artemchep.keyguard.provider.bitwarden.api.obtainSecrets
import com.artemchep.keyguard.provider.bitwarden.api.postConnectToken
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.provider.bitwarden.entity.AccountPreLoginResponse
import com.artemchep.keyguard.provider.bitwarden.entity.ConnectTokenResponse
import com.artemchep.keyguard.provider.bitwarden.entity.KeyConnectorUserKeyResponse
import com.artemchep.keyguard.provider.bitwarden.entity.SsoProfileEntity
import com.artemchep.keyguard.provider.bitwarden.entity.TwoFactorEmailRequestEntity
import com.artemchep.keyguard.provider.bitwarden.entity.toDomain
import com.artemchep.keyguard.provider.bitwarden.model.Login
import com.artemchep.keyguard.provider.bitwarden.model.PasswordResult
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

private const val USER_KEY_BYTES = 64
private const val MASTER_KEY_BYTES = 32

class SsoAuthenticated(
    val authorization: SsoAuthorization,
    val response: ConnectTokenResponse,
    val profile: SsoProfileEntity,
    val login: Login,
    /** The user key, wrapped with the master key. */
    val wrappedUserKey: String,
    /** Where the master key is stored, or `null` to derive it from the master password. */
    val keyConnectorUrl: String? = null,
)

class BitwardenSsoService(
    private val deviceIdUseCase: DeviceIdUseCase,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val cipherEncryptor: CipherEncryptor,
    private val httpClient: HttpClient,
    private val json: Json,
    private val tokenRepository: BitwardenTokenRepository,
    private val profileRepository: BitwardenProfileRepository,
    private val saveAccount: SaveBitwardenAccount,
) {
    fun request(env: ServerEnv, email: String, identifier: String?) =
        SsoRequest.create(env, email, cryptoGenerator, base64Service, identifier)

    fun authenticate(authorization: SsoAuthorization, twoFactor: ServerTwoFactorToken?): IO<SsoAuthenticated> =
        ioEffect(Dispatchers.Default) {
            val env = authorization.request.env
            val response = httpClient.postConnectToken(
                json = json,
                env = env,
                deviceId = deviceIdUseCase().bind(),
                twoFactorToken = twoFactor,
                route = "sso-token",
                // Authorization codes are single-use, including when a response is lost.
                configure = { retry { maxRetries = 0 } },
            ) {
                append("grant_type", "authorization_code")
                append("client_id", SsoRequest.CLIENT_ID)
                append("code", authorization.code)
                append("code_verifier", authorization.request.verifier)
                append("redirect_uri", authorization.redirectUri)
            }
            val login = response.toDomain()
            val options = response.userDecryptionOptions
            val isPasswordless = options?.hasMasterPassword == false
            // An account that still has a master password unlocks with it.
            val keyConnectorUrl = (options?.keyConnectorOption?.keyConnectorUrl ?: response.keyConnectorUrl)
                ?.takeIf { isPasswordless && it.isNotBlank() }
            val canUnlock = !isPasswordless || keyConnectorUrl != null
            val mustSetPassword = response.forcePasswordReset || response.resetMasterPassword == true
            if (!canUnlock || mustSetPassword) {
                throw SsoException(SsoFailure.UNSUPPORTED)
            }
            val profile = httpClient.get(env.api.accounts.profile) {
                headers(env)
                header("cache-control", "no-cache, no-store")
                bearerAuth(response.accessToken)
                attributes.put(routeAttribute, "sso-profile")
            }.bodyOrApiException<SsoProfileEntity>()
            val wrappedKey = options?.masterPasswordUnlock?.wrappedUserKey
                ?: response.key ?: profile.key
            val isKeyConnectorKnown = !profile.usesKeyConnector || keyConnectorUrl != null
            // An account without a key still needs setup.
            if (!isKeyConnectorKnown || profile.forcePasswordReset || wrappedKey.isNullOrBlank()) {
                throw SsoException(SsoFailure.UNSUPPORTED)
            }
            if (!profile.email.equals(authorization.request.email.trim(), ignoreCase = true)) {
                throw SsoException(SsoFailure.ACCOUNT)
            }
            SsoAuthenticated(authorization, response, profile, login, wrappedKey, keyConnectorUrl)
        }

    fun resendEmail(authorization: SsoAuthorization, email: String, sessionToken: String?): IO<Unit> = ioEffect {
        val env = authorization.request.env
        env.api.twoFactor.requestEmailCode(httpClient, env, TwoFactorEmailRequestEntity(
            email = email,
            deviceIdentifier = deviceIdUseCase().bind(),
            ssoEmail2FaSessionToken = sessionToken,
        ))
    }

    fun unlock(accountId: String?, authenticated: SsoAuthenticated, password: String): IO<AccountId> =
        unlock(accountId, authenticated, SsoFailure.PASSWORD) {
            val profile = authenticated.profile
            val unlock = authenticated.response.userDecryptionOptions?.masterPasswordUnlock
            if (unlock != null) {
                val prelogin = AccountPreLoginResponse(
                    kdfSettings = unlock.kdf,
                    salt = unlock.salt,
                ).toDomain(profile.email)
                generateSecrets(cryptoGenerator, password, prelogin.hash, prelogin.salt)
            } else {
                val env = authenticated.authorization.request.env
                obtainSecrets(cryptoGenerator, httpClient, env, profile.email, password)
            }
        }

    fun unlockWithKeyConnector(accountId: String?, authenticated: SsoAuthenticated): IO<AccountId> =
        unlock(accountId, authenticated, SsoFailure.KEY_CONNECTOR) {
            val url = requireNotNull(authenticated.keyConnectorUrl).trimEnd('/') + "/user-keys"
            val response = httpClient.get(url) {
                // A Key Connector is a separate host: it gets the
                // access token, never the server's custom headers.
                header("cache-control", "no-cache, no-store")
                bearerAuth(authenticated.response.accessToken)
                attributes.put(routeAttribute, "sso-key-connector")
            }
            // Network failures keep their own message.
            val key = try {
                response.bodyOrApiException<KeyConnectorUserKeyResponse>().key
            } catch (_: ApiException) {
                null
            } catch (_: HttpException) {
                null
            }
            val masterKey = key
                ?.let { base64Service.decodeOrNull(it) }
                ?.takeIf { it.size == MASTER_KEY_BYTES }
                ?: throw SsoException(SsoFailure.KEY_CONNECTOR)
            generateKeyConnectorSecrets(cryptoGenerator, masterKey)
        }

    /** Saves the account once [secrets] decrypt the user key; [failure] reports secrets that do not. */
    private fun unlock(
        accountId: String?,
        authenticated: SsoAuthenticated,
        failure: SsoFailure,
        secrets: suspend () -> PasswordResult,
    ): IO<AccountId> = ioEffect(Dispatchers.Default) {
        val env = authenticated.authorization.request.env
        val profile = authenticated.profile
        // Checked before the expensive key derivation.
        if (accountId != null) requireMatchingAccount(AccountId(accountId), env, profile)
        val result = secrets()
        val decrypted = try {
            val key = SymmetricCryptoKey2(result.encryptionKey + result.macKey)
            cipherEncryptor.decode2(authenticated.wrappedUserKey, key).data
        } catch (_: Exception) {
            throw SsoException(failure)
        }
        val isUserKey = decrypted.size == USER_KEY_BYTES
        decrypted.fill(0)
        if (!isUserKey) throw SsoException(failure)
        currentCoroutineContext().ensureActive()
        base64Service.bitwardenToken(
            id = accountId ?: cryptoGenerator.uuid(),
            secrets = result,
            login = authenticated.login,
            email = profile.email,
            env = env,
            sso = BitwardenToken.Sso(profile.id, authenticated.authorization.organizationIdentifier),
        )
    }.let(saveAccount::invoke)

    private suspend fun requireMatchingAccount(accountId: AccountId, env: ServerEnv, profile: SsoProfileEntity) {
        val previous = tokenRepository.getById(accountId).bind()
            ?: throw SsoException(SsoFailure.ACCOUNT)
        val previousUserId = previous.sso?.userId
            ?: profileRepository.getById(accountId).toIO().bind()?.profileId
        if (previous.env != BitwardenToken.Environment.of(env) ||
            !previous.user.email.equals(profile.email, ignoreCase = true) || previousUserId != profile.id
        ) throw SsoException(SsoFailure.ACCOUNT)
    }
}
