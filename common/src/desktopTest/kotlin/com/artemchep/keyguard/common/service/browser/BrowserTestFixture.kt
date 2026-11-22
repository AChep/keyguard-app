@file:Suppress("TooManyFunctions") // The fixture and the helpers of the browser tests.

package com.artemchep.keyguard.common.service.browser

import arrow.core.Either
import arrow.core.right
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.AddCliAgentUsageHistoryRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.AddUriCipherRequest
import com.artemchep.keyguard.common.model.CliAgentUsageDetails
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentDuplexChannel
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.fixedAgentApprovalCacheConfig
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentUsageRecorder
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.passkey.toAddCredentialCipherRequest
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistory
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.SealedPendingUsageHistory
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionAccess
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionDependencies
import com.artemchep.keyguard.common.service.session.CliAgentSessionAccess
import com.artemchep.keyguard.common.service.session.testVaultSessionLocker
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.AddCipherUsedAutofillHistory
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddCliAgentUsageHistory
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.AddUriCipher
import com.artemchep.keyguard.common.usecase.AddUrlBlock
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionEnabled
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionRemovedConnectors
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCipherLastUsed
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionEnabled
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionRemovedConnectors
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserFragments
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReassembler
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserVaultState
import com.artemchep.keyguard.feature.generator.PasswordGeneratorConfigReader
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeNoiseKeyPair
import com.artemchep.keyguard.provider.bitwarden.usecase.BlockedUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import com.artemchep.keyguard.provider.keyguard.session.realTime
import com.artemchep.keyguard.provider.keyguard.transport.NoiseByteStream
import com.artemchep.keyguard.provider.keyguard.transport.NoiseSuite
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.createProfile
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticatorDataFactory
import com.artemchep.keyguard.util.webauthn.WebAuthnRegistrationRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnRpIdValidator
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val CHROME_ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/"
internal const val FIREFOX_ORIGIN = "keyguard@artemchep.com"
internal const val TIMEOUT = 10_000L
internal const val ACCOUNT_ID = "account"

/** The details of a browser's row of the request log. */
internal val AddCliAgentUsageHistoryRequest.browserDetails: CliAgentUsageDetails.Browser
    get() = assertIs(details)

/** The last two labels of a host are its registrable domain. */
internal object LastTwoLabelsTldService : TldService {
    override val version: String get() = "test"

    override fun getDomainName(host: String): IO<String> = ioEffect {
        host.split('.').takeLast(2).joinToString(".")
    }
}

/** The browser extension service over a fake vault, wired like the application wires it. */
internal class BrowserFixture(
    private val scope: CoroutineScope,
    /** The file of the pairing store; another fixture over the same one is the app after a restart. */
    val storage: MemoryJsonStore = MemoryJsonStore(),
    /** The settings of the vault, with its record of the pairings; shared after a restart as well. */
    val vaultStorage: MemoryJsonStore = MemoryJsonStore(),
    handshakeTimeoutMs: Long = BrowserProtocol.HANDSHAKE_TIMEOUT_MS,
    /** How long a pairing keeps an approval. */
    approvalWindow: Duration = 1.hours,
    /** How long a prompt waits for the user. */
    approvalTimeout: Duration = 1.minutes,
    /** The clock of the approval windows and the pairing attempts. */
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** The items of the vault. */
    val ciphers = MutableStateFlow<List<DSecret>>(emptyList())

    /** The accounts of the vault. */
    val profiles = MutableStateFlow(listOf(createProfile(ACCOUNT_ID, name = "Work")))

    /** The URIs the user blocked. */
    val urlBlocks = MutableStateFlow<List<DGlobalUrlBlock>>(emptyList())

    /** The equivalent domains of the accounts; the app reads them once, at the first use. */
    val equivalentDomains = MutableStateFlow<List<DEquivalentDomains>>(emptyList())

    /** The saved state of the screens, as `GetScreenState` reads it. */
    val screenState = MutableStateFlow<Map<String, Map<String, Any?>>>(emptyMap())

    /** The items whose use autofill recorded. */
    val autofillHistory = mutableListOf<AddCipherOpenedHistoryRequest>()

    /** The passkeys whose use the vault recorded. */
    val passkeyHistory = mutableListOf<AddCipherUsedPasskeyHistoryRequest>()

    /** While set, reading the last use of the items fails. */
    @Volatile
    var lastUsedFails = false

    /** The pages added to items, as `AddUriCipher` received them. */
    val addedUris = mutableListOf<AddUriCipherRequest>()

    /** While set, adding a page to an item fails. */
    @Volatile
    var addUriFails = false

    /** While set, adding a blocked URI fails. */
    @Volatile
    var addUrlBlockFails = false

    /** Whether the user may write to the vault. */
    val canWrite = MutableStateFlow(true)

    /** The collections of the accounts. */
    val collections = MutableStateFlow<List<DCollection>>(emptyList())

    /** Runs whenever the app reads the prompt timeout, as it does right before it shows a prompt. */
    @Volatile
    var beforePrompt: suspend () -> Unit = {}

    /** The app's log. */
    val log = RecordingLogRepository()

    /** The related origins documents of the relying parties, by host; the others answer 404. */
    val wellKnown = mutableMapOf<String, String>()

    /** The documents the app read, by URL. */
    val fetched = mutableListOf<String>()

    /** The authenticator of the app, with native crypto, so that signatures are real. */
    val authenticator = WebAuthnAuthenticator(
        json = Json,
        authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(ByteArray(16)),
    )

    private val relatedOrigins = HttpClient(
        MockEngine { request ->
            synchronized(fetched) { fetched += request.url.toString() }
            val body = wellKnown[request.url.host]
            if (body != null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        },
    )

    /** The requests the request log recorded while the vault was unlocked. */
    private val recordedUsage = mutableListOf<AddCliAgentUsageHistoryRequest>()

    /** The configurations the generator made passwords with. */
    val generated = mutableListOf<Pair<GeneratorContext, PasswordGeneratorConfig>>()

    private val defaultMatchDetection = object : GetAutofillDefaultMatchDetection {
        override fun invoke(): Flow<DSecret.Uri.MatchType> = flowOf(DSecret.Uri.MatchType.default)
    }

    private val getUrlBlocks = object : GetUrlBlocks {
        override fun invoke(): Flow<List<DGlobalUrlBlock>> = urlBlocks
    }

    private val cipherUrlCheck: CipherUrlCheck = CipherUrlCheckImpl(tldService = LastTwoLabelsTldService)

    private var unlocks = 0

    /** A new unlock of the vault, with a session of its own. */
    private fun unlocked(): MasterSession.Key {
        val vault = object : VaultSession {
            override val id = "session-${++unlocks}"
            override val active = MutableStateFlow(true)
            override fun retire() {
                active.value = false
            }

            override fun close() = retire()
        }
        return MasterSession.Key(
            MasterKey(MasterKdfVersion.V0, ByteArray(32)),
            vault,
            MasterSession.Key.Authenticated,
            Instant.fromEpochSeconds(0),
        )
    }

    val session = MutableStateFlow<MasterSession>(unlocked())
    private val getVaultSession = object : GetVaultSession {
        override val valueOrNull: MasterSession get() = session.value
        override fun invoke(): Flow<MasterSession> = session
    }
    private val dependencies = BrowserExtensionSessionDependencies(
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = ciphers
        },
        getProfiles = object : GetProfiles {
            override fun invoke(): Flow<List<DProfile>> = profiles
        },
        getSuggestions = GetSuggestionsImpl(
            getAutofillDefaultMatchDetection = defaultMatchDetection,
            getUrlBlocks = getUrlBlocks,
            cipherUrlCheck = cipherUrlCheck,
        ),
        equivalentDomainsBuilderFactory = EquivalentDomainsBuilderFactory(
            logRepository = RecordingLogRepository(),
            getEquivalentDomains = object : GetEquivalentDomains {
                override fun invoke(): Flow<List<DEquivalentDomains>> = equivalentDomains
            },
        ),
        getUrlBlocks = getUrlBlocks,
        blockedUrlCheck = BlockedUrlCheckImpl(cipherUrlCheck),
        cipherUrlCheck = cipherUrlCheck,
        getAutofillDefaultMatchDetection = defaultMatchDetection,
        getTotpCode = object : GetTotpCode {
            override fun invoke(token: TotpToken): Flow<Either<Throwable, TotpCode>> = flowOf(
                TotpCode(
                    code = "123456",
                    counter = TotpCode.TimeBasedCounter(
                        timestamp = Clock.System.now(),
                        expiration = Clock.System.now() + 20.seconds,
                        duration = 30.seconds,
                    ),
                ).right(),
            )
        },
        addCipherUsedAutofillHistory = object : AddCipherUsedAutofillHistory {
            override fun invoke(request: AddCipherOpenedHistoryRequest): IO<Unit> = ioEffect {
                synchronized(autofillHistory) { autofillHistory += request }
            }
        },
        // The last of the uses that autofill and the passkeys recorded.
        getCipherLastUsed = object : GetCipherLastUsed {
            override fun invoke(cipherIds: Set<String>): IO<Map<String, Instant>> = ioEffect {
                if (lastUsedFails) throw IOException("The read failed")
                val autofill = synchronized(autofillHistory) { autofillHistory.map { it.cipherId to it.instant } }
                val passkey = synchronized(passkeyHistory) { passkeyHistory.map { it.cipherId to it.instant } }
                (autofill + passkey)
                    .filter { (cipherId, _) -> cipherId in cipherIds }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, instants) -> instants.max() }
            }
        },
        addUriCipher = object : AddUriCipher {
            override fun invoke(request: AddUriCipherRequest): IO<Boolean> = ioEffect {
                synchronized(addedUris) { addedUris += request }
                if (addUriFails) throw IOException("The write failed")
                true
            }
        },
        addUrlBlock = object : AddUrlBlock {
            override fun invoke(model: DGlobalUrlBlock): IO<Unit> = ioEffect {
                if (addUrlBlockFails) throw IOException("The write failed")
                urlBlocks.update { it + model.copy(id = "added-${it.size}") }
            }
        },
        getCanWrite = object : GetCanWrite {
            override fun invoke(): Flow<Boolean> = canWrite
        },
        getCollections = object : GetCollections {
            override fun invoke(): Flow<List<DCollection>> = collections
        },
        addCredentialCipher = object : AddCredentialCipher {
            override fun invoke(request: AddCredentialCipherRequest): IO<AddCredentialCipher.Result> = ioEffect {
                val data = request.data as AddCredentialCipherRequestPasskeyData
                val target = ciphers.value.first { it.id == request.cipherId }
                val login = checkNotNull(target.login)
                val stored = target.copy(
                    login = login.copy(fido2Credentials = login.fido2Credentials + data.toFido2Credentials()),
                )
                ciphers.value = ciphers.value.map { if (it.id == target.id) stored else it }
                AddCredentialCipher.Result.Stored
            }
        },
        addCipherUsedPasskeyHistory = object : AddCipherUsedPasskeyHistory {
            override fun invoke(request: AddCipherUsedPasskeyHistoryRequest): IO<Unit> = ioEffect {
                synchronized(passkeyHistory) { passkeyHistory += request }
            }
        },
        vaultSettings = object : VaultSettingsKeyValueStore, KeyValueStore by JsonKeyValueStore(vaultStorage) {},
    )

    /** The vault's record of the pairings. */
    val vaultPairings get() = BrowserVaultPairingStore(dependencies.vaultSettings)
    private val sessionAccess = BrowserExtensionSessionAccess { key ->
        dependencies.takeIf { key.session.active.value }
    }

    /** The state of the vault, as the extensions see it. */
    val vault: Flow<BrowserVaultState> = BrowserVaultAccess(getVaultSession, sessionAccess).state

    val enabled = MutableStateFlow(true)

    /** The stored browsers whose connectors the user removed. */
    val removedConnectors = MutableStateFlow<Set<BrowserConnectorBrowser>>(emptySet())
    val crypto = NativeCryptoGenerator()
    val pairingSettings = JsonKeyValueStore(storage)
    val store = BrowserPairingStore(pairingSettings, Json)

    /** What the passkey requests need beyond the vault, with their pickers. */
    val passkeys = BrowserPasskeys(
        rpIdValidator = WebAuthnRpIdValidator(
            domainLookup = { host -> LastTwoLabelsTldService.getDomainName(host).bind() },
            httpClient = relatedOrigins,
        ),
        authenticator = authenticator,
        vaultSessionLocker = testVaultSessionLocker(scope, timeout = 1.hours) {},
    )
    val service = BrowserExtensionServiceImpl(
        getVaultSession = getVaultSession,
        sessionAccess = sessionAccess,
        getBrowserExtensionEnabled = object : GetBrowserExtensionEnabled {
            override fun invoke(): Flow<Boolean> = enabled
        },
        putBrowserExtensionEnabled = object : PutBrowserExtensionEnabled {
            override fun invoke(value: Boolean): IO<Unit> = ioEffect { enabled.value = value }
        },
        getBrowserExtensionRemovedConnectors = object : GetBrowserExtensionRemovedConnectors {
            override fun invoke(): Flow<Set<BrowserConnectorBrowser>> = removedConnectors
        },
        putBrowserExtensionRemovedConnectors = object : PutBrowserExtensionRemovedConnectors {
            override fun invoke(value: Set<BrowserConnectorBrowser>): IO<Unit> = ioEffect {
                removedConnectors.value = value
            }
        },
        cryptoGenerator = crypto,
        store = store,
        tldService = LastTwoLabelsTldService,
        passkeys = passkeys,
        logRepository = log,
        passwordGenerator = PasswordGeneratorConfigReader(
            getScreenState = object : GetScreenState {
                override fun invoke(key: String): Flow<Map<String, Any?>> =
                    screenState.map { it[key].orEmpty() }
            },
            getPassword = object : GetPassword {
                override fun invoke(
                    context: GeneratorContext,
                    config: PasswordGeneratorConfig,
                ): IO<GetPasswordResult?> = ioEffect {
                    generated += context to config
                    GetPasswordResult.Value("generated-${generated.size}")
                }
            },
        ),
        scope = scope,
        recorder = CliAgentUsageRecorder(
            getVaultSession = getVaultSession,
            sessionAccess = CliAgentSessionAccess {
                object : AddCliAgentUsageHistory {
                    override fun invoke(request: AddCliAgentUsageHistoryRequest): IO<Unit> = ioEffect {
                        synchronized(recordedUsage) { recordedUsage += request }
                    }
                }
            },
            queue = object : PendingUsageHistoryQueue {
                override fun get(): IO<List<SealedPendingUsageHistory>> = ioEffect { emptyList() }
                override fun enqueue(item: PendingUsageHistory): IO<Unit> = ioEffect { }
                override fun remove(id: String): IO<Unit> = ioEffect { }
            },
            json = Json,
            logRepository = RecordingLogRepository(),
        ),
        approvalCacheConfig = fixedAgentApprovalCacheConfig(approvalWindow, AgentApprovalCachePolicy.Default),
        approvalTimeout = {
            beforePrompt()
            approvalTimeout
        },
        handshakeTimeoutMs = handshakeTimeoutMs,
        timeSource = timeSource,
    )

    /** The request log entries recorded so far. */
    fun recorded(): List<AddCliAgentUsageHistoryRequest> = synchronized(recordedUsage) { recordedUsage.toList() }

    init {
        service.configureRuntime(supported = true)
    }

    /** Adds a login of [accountId] at [uri]. */
    @Suppress("LongParameterList")
    fun login(
        id: String,
        uri: String = "https://example.com",
        username: String? = "alice",
        password: String? = "secret-$id",
        totp: String? = null,
        reprompt: Boolean = false,
        accountId: String = ACCOUNT_ID,
        name: String = "Login $id",
    ): DSecret {
        val secret = createSecret(
            id = id,
            name = name,
            accountId = accountId,
            reprompt = reprompt,
            uris = listOf(DSecret.Uri(uri = uri)),
            login = DSecret.Login(
                username = username,
                password = password,
                totp = totp?.let { raw ->
                    DSecret.Login.Totp(
                        raw = raw,
                        token = checkNotNull(TotpToken.parse(raw).getOrNull()),
                    )
                },
            ),
        )
        ciphers.value += secret
        return secret
    }

    /** Locks the vault, which retires its session. */
    fun lock() {
        (session.value as? MasterSession.Key)?.session?.retire()
        session.value = MasterSession.Empty()
    }

    /**
     * Adds a login of [accountId] at [uri] with a passkey of [rpId] that
     * signs for real; returns the passkey and the public key that verifies it.
     */
    @Suppress("LongParameterList")
    fun passkeyLogin(
        id: String,
        rpId: String = "example.com",
        uri: String = "https://$rpId",
        userName: String = "alice",
        discoverable: Boolean = true,
        reprompt: Boolean = false,
        accountId: String = ACCOUNT_ID,
    ): TestPasskey {
        val registration = authenticator.createCredential(
            request = WebAuthnRegistrationRequest(
                rpId = rpId,
                userHandle = PasskeyBase64.encodeToString("user-$id".encodeToByteArray()),
                userName = userName,
                userDisplayName = userName.uppercase(),
                pubKeyCredParams = listOf(CreatePasskeyPubKeyCredParams(alg = -7.0, type = "public-key")),
                discoverable = discoverable,
            ),
            userVerified = true,
        )
        val credential = registration.credential.toAddCredentialCipherRequest().toFido2Credentials()
        val login = login(id = id, uri = uri, username = userName, reprompt = reprompt, accountId = accountId)
        val stored = login.copy(login = login.login?.copy(fido2Credentials = listOf(credential)))
        ciphers.value = ciphers.value.map { if (it.id == id) stored else it }
        return TestPasskey(credential, registration.publicKey.copyOf())
    }

    fun unlock() {
        session.value = unlocked()
    }

    /** The agent's request to open the stream of an extension at [origin]. */
    fun openRequest(
        origin: String = CHROME_ORIGIN,
        hostRevision: Int = 1,
        /** The browser, `null` when the agent can't find it. */
        caller: CallerIdentity? = CallerIdentity(pid = 42, processName = "chrome", appName = "Google Chrome"),
    ) = CliAgentMessages.BrowserOpenRequest(
        caller = caller,
        origin = origin,
        verifiedHost = true,
        hostRevision = hostRevision,
    )

    /** Opens the stream of an extension at [origin], as the agent does; the session serves it. */
    suspend fun open(
        origin: String = CHROME_ORIGIN,
        request: CliAgentMessages.BrowserOpenRequest = openRequest(origin),
    ): BrowserPipe {
        // The setting loads on another thread.
        service.state.first { it.needsAgent }
        val answer = service.open(request)
        assertIs<CliAgentMessages.Response.Kind.BrowserOpen>(answer.response)
        val duplex = checkNotNull(answer.upgrade)
        val pipe = BrowserPipe()
        pipe.serving = scope.launch {
            try {
                duplex.serve(pipe.server)
            } finally {
                duplex.close()
                pipe.end()
            }
        }
        return pipe
    }

    /** Opens a stream and runs the handshake of [keyPair] over it. */
    suspend fun connect(
        keyPair: NativeNoiseKeyPair = NativeCryptoNoise.generateKeyPair(),
        origin: String = CHROME_ORIGIN,
        pinned: ByteArray? = null,
        request: CliAgentMessages.BrowserOpenRequest = openRequest(origin),
    ): BrowserClient {
        val pipe = open(origin, request)
        val noise = NoiseByteStream.handshake(
            stream = pipe.client,
            suite = NoiseSuite.XX,
            initiator = true,
            localPrivateKey = keyPair.privateKey,
            pinnedRemoteStatic = pinned,
            prologue = BrowserProtocol.prologue(origin),
            timeoutMs = TIMEOUT,
        )
        return BrowserClient(pipe, noise, keyPair)
    }
}

/** Both ends of one stream between the agent's connection and the extension. */
internal class BrowserPipe {
    private val toServer = Channel<ByteArray>(Channel.UNLIMITED)
    private val toClient = Channel<ByteArray>(Channel.UNLIMITED)

    lateinit var serving: Job

    /** While set, every write of the app waits for it. */
    @Volatile
    var writeGate: CompletableDeferred<Unit>? = null

    /** While set, every write of the app waits for it once the extension can read the packet. */
    @Volatile
    var sentGate: CompletableDeferred<Unit>? = null

    val server = object : AgentDuplexChannel {
        override suspend fun readPacket(): ByteArray? = toServer.receiveCatching().getOrNull()

        override suspend fun writePacket(packet: ByteArray): Boolean {
            writeGate?.await()
            val sent = toClient.trySend(packet.copyOf()).isSuccess
            sentGate?.await()
            return sent
        }
    }

    val client = object : ByteStream {
        override suspend fun read(): ByteArray? = toClient.receiveCatching().getOrNull()

        override suspend fun write(bytes: ByteArray) {
            toServer.send(bytes.copyOf())
        }

        override suspend fun awaitWritable() = Unit

        override suspend fun close() {
            toServer.close()
        }
    }

    /** The app ended the stream. */
    fun end() {
        toClient.close()
    }
}

/** The extension's end of a session: plaintext messages over Noise. */
internal class BrowserClient(
    val pipe: BrowserPipe,
    val noise: NoiseByteStream,
    val keyPair: NativeNoiseKeyPair,
) {
    private val reassembler = BrowserReassembler()

    /** Sends [text], split into fragments of at most [maxData] bytes. */
    suspend fun send(text: String, maxData: Int = BrowserFragments.MAX_FRAGMENT_DATA) {
        BrowserFragments.split(text.encodeToByteArray(), maxData).forEach { noise.write(it) }
    }

    /** The next message, or `null` once the app ended the session. */
    suspend fun receive(): JsonObject? = withTimeout(TIMEOUT) { next() }

    private suspend fun next(): JsonObject? {
        while (true) {
            val fragment = noise.read()
                ?: return null
            val message = reassembler.add(fragment)
                ?: continue
            return Json.parseToJsonElement(message.decodeToString()).jsonObject
        }
    }

    /** Sends a request, then returns the next message, its response. */
    suspend fun call(text: String): JsonObject {
        send(text)
        return checkNotNull(receive()) { "The session ended" }
    }

    suspend fun hello(id: Int = 1): JsonObject =
        call("""{"id":$id,"type":"hello","protocol":1,"capabilities":["passkeys","save"]}""")

    /** Waits until the app ends the session, skipping what it sends meanwhile. */
    suspend fun awaitEnd() {
        while (receive() != null) Unit
        withTimeout(TIMEOUT) { pipe.serving.join() }
    }
}

/** A passkey of the vault, and the public key that verifies its signatures. */
internal class TestPasskey(
    val credential: DSecret.Login.Fido2Credentials,
    /** SubjectPublicKeyInfo. */
    val publicKey: ByteArray,
)

internal fun AddCredentialCipherRequestPasskeyData.toFido2Credentials() = DSecret.Login.Fido2Credentials(
    credentialId = checkNotNull(credentialId),
    keyType = keyType,
    keyAlgorithm = keyAlgorithm,
    keyCurve = keyCurve,
    keyValue = keyValue,
    rpId = rpId,
    rpName = rpName,
    counter = counter,
    userHandle = userHandle,
    userName = userName,
    userDisplayName = userDisplayName,
    discoverable = discoverable,
    creationDate = Instant.fromEpochSeconds(0),
    prfSecret = prfSecret,
)

internal class MemoryJsonStore : JsonKeyValueStoreStore {
    @Volatile
    private var state: PersistentMap<String, Any?> = persistentMapOf()

    /** While set, every write fails and changes nothing. */
    @Volatile
    var failWrites = false

    /** Runs before every write. */
    @Volatile
    var beforeWrite: suspend () -> Unit = {}

    override fun read() = ioEffect { state }

    override fun write(state: PersistentMap<String, Any?>) = ioEffect {
        beforeWrite()
        if (failWrites) throw IOException("The write failed")
        this@MemoryJsonStore.state = state
    }
}

/** Pairs [client] through a prompt that [answer] answers; returns the prompt and the response. */
internal suspend fun pair(
    fixture: BrowserFixture,
    client: BrowserClient,
    answer: (BrowserApprovalRequest) -> Unit,
): Pair<BrowserApprovalRequest, JsonObject> {
    val nonce = ByteArray(32) { (it + 0x80).toByte() }
    val commit = client.call(commit(2, nonce))
    val appNonce = Base64.decode(checkNotNull(commit.string("nonce")))
    client.send(reveal(3, nonce))
    val prompt = fixture.service.approvals.first { it.isNotEmpty() }.single()
    // The user compares the code with the one the extension shows.
    val code = BrowserPairingCode.code(
        handshakeHash = client.noise.handshakeHash,
        appStatic = client.noise.remoteStatic,
        extensionStatic = client.keyPair.publicKey,
        appNonce = appNonce,
        extensionNonce = nonce,
        crypto = fixture.crypto,
    )
    assertEquals(code, (prompt.operation as BrowserOperation.Pair).code)
    assertEquals(CHROME_ORIGIN, prompt.session.origin)
    answer(prompt)
    return prompt to checkNotNull(client.receive())
}

internal suspend fun paired(fixture: BrowserFixture): BrowserClient {
    val client = fixture.connect()
    client.hello()
    pair(fixture, client) { fixture.service.respondToApproval(it.id, true) }
    return client
}

/** The paired extensions outside of the vault, oldest first. */
internal suspend fun BrowserPairingStore.pairings(): List<BrowserPairing> =
    readRecords().orEmpty().map(BrowserPairingRecord::pairing)

internal fun commit(id: Int, nonce: ByteArray): String {
    val commit = BrowserPairingCode.commit(nonce, NativeCryptoGenerator())
    return """{"id":$id,"type":"pair_commit","commit":"${Base64.encode(commit)}","name":"Chrome"}"""
}

internal fun reveal(id: Int, nonce: ByteArray) =
    """{"id":$id,"type":"pair_reveal","nonce":"${Base64.encode(nonce)}"}"""

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.boolean

internal fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.content?.toInt()

internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toLong()

/** Runs [block] in real time with a scope for the app's sessions, cancelled at the end. */
internal fun browserTest(block: suspend CoroutineScope.(scope: CoroutineScope) -> Unit) = realTime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        block(scope)
    } finally {
        scope.cancel()
    }
}

/** Skips events until the response to the request [id] arrives. */
internal suspend fun BrowserClient.receiveResponse(id: Long): JsonObject {
    while (true) {
        val message = checkNotNull(receive()) { "The session ended" }
        if (message["id"]?.jsonPrimitive?.content == id.toString()) return message
    }
}
