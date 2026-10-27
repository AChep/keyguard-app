package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.common.service.totp.impl.TotpServiceImpl
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.copy.Base32ServiceJvm
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.sync
import com.artemchep.keyguard.provider.bitwarden.api.login
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.appendOrganizationToken
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken
import com.artemchep.keyguard.provider.bitwarden.crypto.appendUserToken
import com.artemchep.keyguard.provider.bitwarden.entity.SyncEntity
import com.artemchep.keyguard.provider.bitwarden.model.Login
import com.artemchep.keyguard.provider.bitwarden.model.PasswordResult
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import java.util.Collections

/** An account logged in through Keyguard's own Bitwarden client. */
internal class KeyguardSession(
    val account: TestAccount,
    val secrets: PasswordResult,
    val login: Login,
    /** Assembled like `AddAccountImpl` does, with a fresh id. */
    val token: BitwardenToken,
    val env: ServerEnv,
)

/**
 * Keyguard's HTTP client and crypto, and the seeding helpers, for the Bitwarden-compatible
 * server at [baseUrl]. [VaultwardenFixture] adds a server in Docker.
 */
internal open class VaultwardenApi(
    val baseUrl: String,
) : AutoCloseable {
    val env = ServerEnv(baseUrl = baseUrl)
    val json = createApplicationJson()
    val cryptoGenerator = CryptoGeneratorJvm()
    val base64Service = Base64ServiceJvm()
    val base32Service = Base32ServiceJvm()
    val cipherEncryptor = CipherEncryptorImpl(
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    )
    val totpService = TotpServiceImpl(
        base32Service = base32Service,
        cryptoGenerator = cryptoGenerator,
    )

    /** Every request that [httpClient] sent, as `"METHOD /path"`, oldest first. */
    val recordedRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Configured like the app's client, so Keyguard's API calls can share it. */
    val httpClient = newHttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }.apply {
        plugin(HttpSend).intercept { request ->
            recordedRequests += "${request.method.value} ${request.url.encodedPath}"
            execute(request)
        }
    }

    /** The device of every Keyguard login. */
    val deviceIdUseCase = DeviceIdUseCase(InMemoryIdRepository())

    val accounts = VaultwardenAccounts(
        baseUrl = baseUrl,
        httpClient = httpClient,
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
        base64Service = base64Service,
    )
    val twoFactor = VaultwardenTwoFactor(
        baseUrl = baseUrl,
        httpClient = httpClient,
        totpService = totpService,
    )
    val organizations = VaultwardenOrganizations(
        baseUrl = baseUrl,
        httpClient = httpClient,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        accounts = accounts,
    )
    val sends = VaultwardenSends(
        baseUrl = baseUrl,
        httpClient = httpClient,
    )

    fun clearRecordedRequests() = recordedRequests.clear()

    /** A client that decodes JSON like the app's, plus [configure]. */
    fun newHttpClient(configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(json))
        }
        configure()
    }

    suspend fun deviceId(): String = deviceIdUseCase().invoke()

    /** Keyguard's `login`: the prelogin picks the KDF, which derives the master key, then the token request. */
    suspend fun keyguardPasswordLogin(
        email: String,
        password: String,
        twoFactorToken: ServerTwoFactorToken? = null,
        httpClient: HttpClient = this.httpClient,
    ): Pair<PasswordResult, Login> = login(
        deviceIdUseCase = deviceIdUseCase,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        httpClient = httpClient,
        json = json,
        env = env,
        twoFactorToken = twoFactorToken,
        clientSecret = null,
        email = email,
        password = password,
    )

    /** Logs in with Keyguard's `login` and assembles the token the way `AddAccountImpl` does. */
    suspend fun keyguardLogin(
        account: TestAccount,
        twoFactorToken: ServerTwoFactorToken? = null,
    ): KeyguardSession {
        val (secrets, login) = keyguardPasswordLogin(
            email = account.email,
            password = account.password,
            twoFactorToken = twoFactorToken,
        )
        val token = BitwardenToken(
            id = cryptoGenerator.uuid(),
            key = BitwardenToken.Key(
                masterKeyBase64 = base64Service.encodeToString(secrets.masterKey),
                passwordKeyBase64 = base64Service.encodeToString(secrets.passwordKey),
                encryptionKeyBase64 = base64Service.encodeToString(secrets.encryptionKey),
                macKeyBase64 = base64Service.encodeToString(secrets.macKey),
            ),
            token = BitwardenToken.Token(
                refreshToken = login.refreshToken,
                accessToken = login.accessToken,
                expirationDate = login.accessTokenExpiryDate,
            ),
            user = BitwardenToken.User(
                email = account.email,
            ),
            env = BitwardenToken.Environment.of(
                model = env,
            ),
        )
        return KeyguardSession(account = account, secrets = secrets, login = login, token = token, env = env)
    }

    /** `GET /api/sync` through Keyguard's API client. */
    suspend fun keyguardSync(session: KeyguardSession): SyncEntity = session.env.api
        .sync(httpClient, session.env, session.login.accessToken)

    /** Keyguard's decoders for the user, the profile and every organization of [sync]. */
    fun crypto(session: KeyguardSession, sync: SyncEntity): BitwardenCrImpl = BitwardenCrImpl(
        cipherEncryptor = cipherEncryptor,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    ).apply {
        appendUserToken(
            encKey = session.secrets.encryptionKey,
            macKey = session.secrets.macKey,
        )
        appendProfileToken(
            keyCipherText = sync.profile.key,
            privateKeyCipherText = sync.profile.privateKey,
        )
        sync.profile.organizations.orEmpty().forEach { organization ->
            appendOrganizationToken(
                id = organization.id,
                keyCipherText = organization.key,
            )
        }
    }

    fun decryptWith(cr: BitwardenCrImpl, key: BitwardenCrKey, cipherText: String): ByteArray {
        val decoder = cr.decoder(key)
        return decoder(cipherText).data
    }

    override fun close() {
        httpClient.close()
    }
}
