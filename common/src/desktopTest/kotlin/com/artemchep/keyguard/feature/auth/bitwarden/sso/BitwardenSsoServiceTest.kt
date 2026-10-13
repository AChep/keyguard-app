package com.artemchep.keyguard.feature.auth.bitwarden.sso

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfileRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.BitwardenTokenRepositoryImpl
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaRoute
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.api.generateSecrets
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.provider.bitwarden.entity.ConnectTokenResponse
import com.artemchep.keyguard.provider.bitwarden.entity.SsoProfileEntity
import com.artemchep.keyguard.provider.bitwarden.entity.toDomain
import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.asText
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.BitwardenSsoService
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SaveBitwardenAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SsoAuthenticated
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

// Keep the cases together so they share the same server fixtures.
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class BitwardenSsoServiceTest {
    private val json = createApplicationJson()
    private val crypto = NativeCryptoGenerator()
    private val base64 = Base64ServiceImpl()
    private val cipher = NativeCipherEncryptor(crypto, base64)
    private val authorization = SsoAuthorization(
        SsoRequest(ServerEnv(baseUrl = "https://vault.test"), "user@example.test", "state", "verifier", "challenge"),
        "code", "http://localhost:8065", "organization",
    )
    private val profile = """{
        "id":"server-user","email":"user@example.test","emailVerified":true,"culture":"en-US",
        "key":"wrapped","privateKey":"private","object":"profile","premium":false,
        "securityStamp":"stamp","twoFactorEnabled":false
    }"""
    private val token = """{
        "access_token":"access","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh"
    }"""
    private val args = BitwardenSsoRoute.Args(env = authorization.request.env, email = authorization.request.email)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val saved = mutableListOf<BitwardenToken>()
    private val deviceId = DeviceIdUseCase(object : IdRepository {
        override fun get() = ioEffect { "device" }
        override fun put(id: String) = ioEffect { Unit }
    })
    private val database = object : VaultDatabaseManager {
        override fun get(): IO<Database> = error("Database must not be read for a new account")
        override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> =
            error("Unexpected database mutation")
        override fun changePassword(newMasterKey: MasterKey): IO<Unit> = error("Unexpected password change")
    }

    @BeforeTest
    fun setup() { Dispatchers.setMain(Dispatchers.Unconfined) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    private fun service(
        client: HttpClient,
        database: VaultDatabaseManager = this.database,
        save: SaveBitwardenAccount = SaveBitwardenAccount { login ->
            ioEffect {
                val value = login.bind()
                saved += value
                AccountId(value.id)
            }
        },
    ) = BitwardenSsoService(
        deviceId, crypto, base64, cipher, client, json,
        BitwardenTokenRepositoryImpl(database, Dispatchers.Unconfined),
        BitwardenProfileRepositoryImpl(database, Dispatchers.Unconfined),
        save,
    )

    private fun client(handler: MockRequestHandler) = HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) {
            register(ContentType.Application.Json, KotlinxSerializationConverter(json))
        }
    }

    /** A user key that only the master key [masterKey] decrypts. */
    private fun wrapUserKey(masterKey: ByteArray): String {
        val encKey = crypto.hkdf(masterKey, info = "enc".encodeToByteArray())
        val macKey = crypto.hkdf(masterKey, info = "mac".encodeToByteArray())
        return cipher.encode2(CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            ByteArray(64) { it.toByte() }, SymmetricCryptoKey2(encKey + macKey))
    }

    private fun keyConnectorToken(
        wrapped: String?,
        options: String = """"HasMasterPassword":false,"KeyConnectorOption":{"KeyConnectorUrl":"https://kc.test/"}""",
    ) = token.dropLast(1) + wrapped?.let { ""","Key":"$it"""" }.orEmpty() + ""","UserDecryptionOptions":{$options}}"""

    /** The server answers with [token]; the Key Connector lives at `kc.test`. */
    private fun keyConnectorClient(
        token: String,
        profile: String = this.profile,
        keyConnector: MockRequestHandler = { error("Unexpected Key Connector request") },
    ) = client { request ->
        when {
            request.url.host == "kc.test" -> {
                assertEquals("/user-keys", request.url.encodedPath)
                keyConnector(this, request)
            }
            request.url.encodedPath.endsWith("token") -> respond(token, headers = jsonHeaders)
            else -> respond(profile, headers = jsonHeaders)
        }
    }

    @Test
    fun cancelledAttemptCannotClearReplacementAndScreenCloseDisposesIt() = runTest {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = client { error("Cancelled browser must not send requests") }
        try {
            val state = screen(lifetime).bitwardenSsoStateFlow(service(client), args, autoStart = true,
                onTwoFactor = { error("Unexpected challenge") }, onSuccess = { error("Unexpected success") })
            val old = assertNotNull(state.first().browser)
            assertNotNull(state.first().onRetry).invoke()
            val current = assertNotNull(state.first { it.browser !== old }.browser)
            assertTrue(old.result.isCancelled)
            old.complete("bitwarden://sso-callback?code=late&state=${old.request.state}", SsoRequest.NATIVE_CALLBACK)
            assertFalse(current.result.isCompleted)
            assertEquals(current, state.first().browser)
            lifetime.coroutineContext.job.cancelAndJoin()
            assertTrue(current.result.isCancelled)
            assertTrue(saved.isEmpty())
        } finally {
            lifetime.cancel()
            client.close()
        }
    }

    @Test
    fun twoFactorContinuationOpensVaultUnlockWithoutSavingAccount() = runBlocking {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var requests = 0
        val client = client { request ->
            if (request.url.encodedPath.endsWith("token")) {
                requests++
                if (parseQueryString(request.body.asText())["twoFactorToken"] == null) {
                    respond("""{"error":"invalid_grant","TwoFactorProviders":[0]}""",
                        HttpStatusCode.BadRequest, jsonHeaders)
                } else {
                    respond(token, headers = jsonHeaders)
                }
            } else {
                respond(profile, headers = jsonHeaders)
            }
        }
        try {
            withTimeout(10_000) {
                val challenge = CompletableDeferred<BitwardenLoginTwofaRoute.Args>()
                val state = screen(lifetime).bitwardenSsoStateFlow(service(client), args, autoStart = true,
                    onTwoFactor = { challenge.complete(it) }, onSuccess = { error("Unexpected success") })
                val browser = assertNotNull(state.first().browser)
                browser.complete("bitwarden://sso-callback?code=code&state=${browser.request.state}",
                    SsoRequest.NATIVE_CALLBACK)
                val continuation = challenge.await().continuation
                // A 2FA screen that never reports back must not strand the attempt.
                assertNotNull(state.first().onRetry)
                val twoFactor = ServerTwoFactorToken("123456", TwoFactorProviderType.Authenticator, false)
                continuation.authenticate(twoFactor).bind()
                assertNull(state.first { it.password != null }.onRetry)
                continuation.onDismiss()
                assertNotNull(state.first().password)
                assertTrue(saved.isEmpty())
                // The password step has no retry; closing the screen ends the attempt.
                lifetime.coroutineContext.job.cancelAndJoin()
                // The 2FA screen is not cancelled, so a stale attempt must fail visibly.
                val stale = assertFailsWith<SsoException> { continuation.authenticate(twoFactor).bind() }
                assertEquals(SsoFailure.AUTHENTICATION, stale.failure)
                assertEquals(2, requests)
            }
        } finally {
            lifetime.cancel()
            client.close()
        }
    }

    @Test
    fun failedBrowserSignInOffersRetry() = runTest {
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val messages = mutableListOf<Any?>()
        val client = client { error("A failed browser must not send requests") }
        try {
            val state = screen(lifetime, messages).bitwardenSsoStateFlow(service(client), args, autoStart = true,
                onTwoFactor = { error("Unexpected challenge") }, onSuccess = { error("Unexpected success") })
            // A browser tab closed without a callback must not leave only the timeout.
            assertNotNull(state.first().onRetry)
            assertNotNull(state.first().browser).fail()
            val failed = state.first { it.browser == null }
            assertFalse(failed.isLoading)
            assertEquals(SsoFailure.AUTHENTICATION, (messages.single() as SsoException).failure)
            assertNotNull(failed.onRetry).invoke()
            assertNotNull(state.first { it.browser != null }.browser)
        } finally {
            lifetime.cancel()
            client.close()
        }
    }

    @Test
    fun authorizationCodeAndTwoFactorAreExchangedWithoutPasswordGrant() = runTest {
        val bodies = mutableListOf<Parameters>()
        val client = client { request ->
            when (request.url.encodedPath) {
                "/identity/connect/token" -> {
                    bodies += parseQueryString(request.body.asText())
                    respond(token, headers = jsonHeaders)
                }
                "/api/accounts/profile" -> {
                    assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
                    respond(profile, headers = jsonHeaders)
                }
                else -> error("Unexpected endpoint")
            }
        }
        client.use {
            val twoFactor = ServerTwoFactorToken(" 123456 ", TwoFactorProviderType.Authenticator, true)
            service(it).authenticate(authorization, twoFactor).bind()
        }
        assertEquals(1, bodies.size)
        with(bodies.single()) {
            assertEquals("authorization_code", get("grant_type"))
            assertEquals("desktop", get("client_id"))
            assertEquals("verifier", get("code_verifier"))
            assertEquals("http://localhost:8065", get("redirect_uri"))
            assertEquals("123456", get("twoFactorToken"))
            assertEquals("1", get("twoFactorRemember"))
            assertEquals("device", get("deviceIdentifier"))
            assertNull(get("password"))
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun authenticationRejectsWrongIdentityAndPasswordlessAccounts() = runTest {
        for ((response, expected) in listOf(
            token to SsoFailure.ACCOUNT,
            token.dropLast(1) + ",\"UserDecryptionOptions\":{\"HasMasterPassword\":false}}" to SsoFailure.UNSUPPORTED,
        )) {
            val client = client { request ->
                val body = if (request.url.encodedPath.endsWith("token")) response else
                    profile.replace("user@example.test", "other@example.test")
                respond(body, headers = jsonHeaders)
            }
            client.use {
                val failure = assertFailsWith<SsoException> { service(it).authenticate(authorization, null).bind() }
                assertEquals(expected, failure.failure)
            }
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun keyConnectorIsOnlyUsedByPasswordlessAccountsWithKeys() = runTest {
        val option = """"KeyConnectorOption":{"KeyConnectorUrl":"https://kc.test"}"""
        // An account that was not converted yet keeps its master password.
        keyConnectorClient(keyConnectorToken("wrapped", """"HasMasterPassword":true,$option""")).use {
            assertNull(service(it).authenticate(authorization, null).bind().keyConnectorUrl)
        }
        // Older servers report the Key Connector only at the top level.
        val legacy = keyConnectorToken("wrapped", """"HasMasterPassword":false""")
            .dropLast(1) + ""","KeyConnectorUrl":"https://kc.test"}"""
        keyConnectorClient(legacy).use {
            assertEquals("https://kc.test", service(it).authenticate(authorization, null).bind().keyConnectorUrl)
        }
        // An account without keys needs setup in the web vault first.
        keyConnectorClient(keyConnectorToken(null), profile.replace("\"key\":\"wrapped\",", "")).use {
            val failure = assertFailsWith<SsoException> { service(it).authenticate(authorization, null).bind() }
            assertEquals(SsoFailure.UNSUPPORTED, failure.failure)
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun keyConnectorKeyUnlocksVaultWithoutPassword() = runTest {
        val masterKey = crypto.seed(32)
        val env = ServerEnv(baseUrl = "https://vault.test", headers = listOf(ServerHeader("X-Proxy", "secret")))
        val authorization = SsoAuthorization(
            SsoRequest(env, "user@example.test", "state", "verifier", "challenge"),
            "code", "http://localhost:8065", "organization",
        )
        var requests = 0
        val client = keyConnectorClient(keyConnectorToken(wrapUserKey(masterKey))) { request ->
            requests++
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Proxy"])
            respond("""{"key":"${base64.encodeToString(masterKey)}"}""", headers = jsonHeaders)
        }
        client.use {
            val sso = service(it)
            val authenticated = sso.authenticate(authorization, null).bind()
            assertEquals("https://kc.test/", authenticated.keyConnectorUrl)
            sso.unlockWithKeyConnector(null, authenticated).bind()
        }
        assertEquals(1, requests)
        with(saved.single()) {
            assertEquals(base64.encodeToString(masterKey), key.masterKeyBase64)
            assertEquals("", key.passwordKeyBase64)
            assertEquals("server-user", sso?.userId)
            assertEquals("refresh", token?.refreshToken)
        }
    }

    @Test
    fun keyConnectorFailureSavesNothing() = runTest {
        val wrapped = wrapUserKey(crypto.seed(32))
        for (keyConnector in listOf<MockRequestHandler>(
            { respond("""{"message":"Not found"}""", HttpStatusCode.NotFound, jsonHeaders) },
            { respond("<html>Bad gateway</html>", HttpStatusCode.BadGateway) },
            { respond("""{"key":"not base64!"}""", headers = jsonHeaders) },
            { respond("""{"key":"${base64.encodeToString(crypto.seed(16))}"}""", headers = jsonHeaders) },
            // A key that does not decrypt the vault.
            { respond("""{"key":"${base64.encodeToString(crypto.seed(32))}"}""", headers = jsonHeaders) },
        )) {
            keyConnectorClient(keyConnectorToken(wrapped), keyConnector = keyConnector).use {
                val sso = service(it)
                val authenticated = sso.authenticate(authorization, null).bind()
                val failure = assertFailsWith<SsoException> { sso.unlockWithKeyConnector(null, authenticated).bind() }
                assertEquals(SsoFailure.KEY_CONNECTOR, failure.failure)
            }
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun keyConnectorAccountUnlocksWithoutPasswordStep() = runBlocking {
        val masterKey = crypto.seed(32)
        val token = keyConnectorToken(wrapUserKey(masterKey))
        for (twoFactor in listOf(false, true)) {
            val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val client = client { request ->
                when {
                    request.url.host == "kc.test" ->
                        respond("""{"key":"${base64.encodeToString(masterKey)}"}""", headers = jsonHeaders)
                    !request.url.encodedPath.endsWith("token") -> respond(profile, headers = jsonHeaders)
                    twoFactor && parseQueryString(request.body.asText())["twoFactorToken"] == null ->
                        respond("""{"error":"invalid_grant","TwoFactorProviders":[0]}""",
                            HttpStatusCode.BadRequest, jsonHeaders)
                    else -> respond(token, headers = jsonHeaders)
                }
            }
            try {
                withTimeout(10_000) {
                    val success = CompletableDeferred<Unit>()
                    val challenge = CompletableDeferred<BitwardenLoginTwofaRoute.Args>()
                    val state = screen(lifetime).bitwardenSsoStateFlow(service(client), args, autoStart = true,
                        onTwoFactor = { challenge.complete(it) }, onSuccess = { success.complete(Unit) })
                    val browser = assertNotNull(state.first().browser)
                    browser.complete("bitwarden://sso-callback?code=code&state=${browser.request.state}",
                        SsoRequest.NATIVE_CALLBACK)
                    if (twoFactor) {
                        val continuation = challenge.await().continuation
                        val code = ServerTwoFactorToken("123456", TwoFactorProviderType.Authenticator, false)
                        continuation.authenticate(code).bind()
                        assertNull(state.first().password)
                        // The vault unlocks once the 2FA screen closes.
                        assertFalse(success.isCompleted)
                        continuation.onDismiss()
                    }
                    success.await()
                    assertNull(state.first().password)
                }
            } finally {
                lifetime.cancel()
                client.close()
            }
        }
        assertEquals(2, saved.size)
    }

    @Test
    fun leavingTheScreenDoesNotInterruptSavingTheAccount() = runBlocking {
        val masterKey = crypto.seed(32)
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = keyConnectorClient(keyConnectorToken(wrapUserKey(masterKey))) {
            respond("""{"key":"${base64.encodeToString(masterKey)}"}""", headers = jsonHeaders)
        }
        // Stands for the database insert and the sync and backup after it.
        val save = SaveBitwardenAccount { login ->
            ioEffect {
                val value = login.bind()
                saving.complete(Unit)
                release.await()
                saved += value
                AccountId(value.id)
            }
        }
        var succeeded = false
        try {
            withTimeout(10_000) {
                val state = screen(lifetime, app = app).bitwardenSsoStateFlow(service(client, save = save), args,
                    autoStart = true, onTwoFactor = { error("Unexpected challenge") }, onSuccess = { succeeded = true })
                val browser = assertNotNull(state.first().browser)
                browser.complete("bitwarden://sso-callback?code=code&state=${browser.request.state}",
                    SsoRequest.NATIVE_CALLBACK)
                saving.await()
                lifetime.coroutineContext.job.cancelAndJoin()
                release.complete(Unit)
                app.coroutineContext.job.children.forEach { it.join() }
                assertEquals(1, saved.size)
                assertFalse(succeeded)
            }
        } finally {
            lifetime.cancel()
            app.cancel()
            client.close()
        }
    }

    @Test
    fun vaultPasswordMustDecryptKeyBeforeAccountIsSaved() = runTest {
        val password = "vault password"
        for (kdf in listOf(PreLogin.Hash.Pbkdf2(600000), PreLogin.Hash.Argon2id(2, 16, 1))) {
            val secrets = generateSecrets(crypto, password, kdf, "custom-salt")
            val wrapped = cipher.encode2(CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
                ByteArray(64) { it.toByte() }, SymmetricCryptoKey2(secrets.encryptionKey + secrets.macKey))
            val kdfJson = when (kdf) {
                is PreLogin.Hash.Pbkdf2 -> """{"KdfType":0,"Iterations":600000}"""
                is PreLogin.Hash.Argon2id -> """{"KdfType":1,"Iterations":2,"Memory":16,"Parallelism":1}"""
            }
            val response = json.decodeFromString<ConnectTokenResponse>(token.dropLast(1) + """,
                "UserDecryptionOptions":{"HasMasterPassword":true,"MasterPasswordUnlock":{
                    "Salt":"custom-salt","Kdf":$kdfJson,"MasterKeyWrappedUserKey":"$wrapped"
                }}}""")
            val authenticated = SsoAuthenticated(
                authorization, response, json.decodeFromString<SsoProfileEntity>(profile),
                login = response.toDomain(),
                wrappedUserKey = wrapped,
            )
            client { error("Modern unlock metadata must not need prelogin") }.use {
                val sso = service(it)
                val size = saved.size
                val failure = assertFailsWith<SsoException> { sso.unlock(null, authenticated, "wrong").bind() }
                assertEquals(SsoFailure.PASSWORD, failure.failure)
                assertEquals(size, saved.size)
                sso.unlock(null, authenticated, password).bind()
                assertEquals(size + 1, saved.size)
                assertEquals("server-user", saved.last().sso?.userId)
                assertEquals(base64.encodeToString(secrets.masterKey), saved.last().key.masterKeyBase64)
                assertEquals("refresh", saved.last().token?.refreshToken)
                assertEquals(authenticated.login.accessTokenExpiryDate, saved.last().token?.expirationDate)
                val previous = saved.last()
                val db = createUploadTestDatabase()
                db.accountQueries.insert(accountId = previous.id, data = previous)
                val reauth = service(it, UploadTestVaultDatabaseManager(db))
                reauth.unlock(previous.id, authenticated, password).bind()
                assertEquals(previous.id, saved.last().id)
                val count = saved.size
                for (changed in listOf(
                    previous.copy(sso = BitwardenToken.Sso("different-user", "organization")),
                    previous.copy(env = previous.env.copy(baseUrl = "https://another.test")),
                    previous.copy(user = BitwardenToken.User("other@example.test")),
                )) {
                    db.accountQueries.insert(accountId = previous.id, data = changed)
                    val mismatch = assertFailsWith<SsoException> {
                        reauth.unlock(previous.id, authenticated, password).bind()
                    }
                    assertEquals(SsoFailure.ACCOUNT, mismatch.failure)
                    assertEquals(count, saved.size)
                }
            }
        }
    }

    @Test
    fun emailResendUsesSsoProofAndDeviceWithoutPasswordHash() = runTest {
        var body = ""
        val client = client { request ->
            assertEquals("/api/two-factor/send-email-login", request.url.encodedPath)
            body = request.body.asText()
            respond("{}", headers = jsonHeaders)
        }
        client.use { service(it).resendEmail(authorization, "user@example.test", "proof").bind() }
        assertTrue(body.contains("proof"))
        assertTrue(body.contains("device"))
        assertFalse(body.contains("masterPasswordHash"))
    }
}

/** Only the screen and app lifetimes and the screen's messages are faked. */
private fun screen(
    lifetime: CoroutineScope,
    messages: MutableList<Any?> = mutableListOf(),
    app: CoroutineScope = lifetime,
) =
    Proxy.newProxyInstance(
        RememberStateFlowScope::class.java.classLoader,
        arrayOf(RememberStateFlowScope::class.java),
    ) { _, method, methodArgs ->
        when (method.name) {
            "getScreenScope" -> lifetime
            "getAppScope" -> app
            "message" -> messages.add(methodArgs?.single()).let { null }
            else -> error("Unexpected screen method: ${method.name}")
        }
    } as RememberStateFlowScope
