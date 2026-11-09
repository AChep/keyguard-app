package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.common.service.http.KeyguardHttpUserAgent
import com.artemchep.keyguard.common.service.http.configureKeyguardHttpClient
import com.artemchep.keyguard.common.service.keyvalue.impl.SqlDelightVaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepositoryImpl
import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistRepositoryImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.DatabaseSqlManagerInFileJvm
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.model.EntryDelete
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.provider.bitwarden.usecase.RevokeKeyguardDeviceImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.LeaveKeyguardGroupImpl
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkComponents
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkWorker
import com.artemchep.keyguard.provider.keyguard.crypto.NativeP2pCrypto
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentCoordinator
import com.artemchep.keyguard.provider.keyguard.session.KeyguardMailboxTimings
import com.artemchep.keyguard.provider.keyguard.session.KeyguardRoomConnector
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionStatus
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardInbox
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import com.artemchep.keyguard.provider.keyguard.transport.GzipCompressor
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardIceServers
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KtorKeyguardRelayMailboxes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One app of the tests, as its vault session sets it up: its own encrypted vault database,
 * HTTP client, relay connector and [transportFactory], the email forwarders and wordlists,
 * and linked apps wired as `VaultSyncModule` wires them: the link, its sync passes, the
 * sessions, relay storage, pairing and the removal of devices. Relay storage asks and
 * posts far more often than in the app, see [MAILBOX_TIMINGS].
 *
 * [startLive] runs the [AppLinkWorker], as the app does in the foreground: it holds the
 * session of the link while this app is linked to other devices. Without it the app is in
 * the background, like Android's background sync: each sync pass connects once.
 */
// The wiring and the actions of a whole app.
@Suppress("TooManyFunctions")
internal class P2pE2eDevice(
    val name: String,
    relay: RelayServer,
    transportFactory: KeyguardTransportFactory,
    /** `true` if the user of this app turned relay storage on; it links with it. */
    relayStorage: Boolean = false,
) : AutoCloseable {
    private val directory: File = Files.createTempDirectory("keyguard-p2p-e2e-").toFile()

    private val cryptoGenerator = NativeCryptoGenerator()

    private val crypto = NativeP2pCrypto(cryptoGenerator)

    private val json = createApplicationJson()

    private val sqlManager = ClosingSqlManager(
        DatabaseSqlManagerInFileJvm(fileIo = io(File(directory, "database.sqlite"))),
    )

    private val db: VaultDatabaseManager = VaultDatabaseManagerImpl(
        logRepository = NoopLogRepository,
        json = json,
        sqlManager = sqlManager,
        masterKey = MasterKey(
            version = MasterKdfVersion.LATEST,
            byteArray = cryptoGenerator.seed(MASTER_KEY_LENGTH),
        ),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(name))

    private val httpClient = HttpClient(OkHttp) {
        configureKeyguardHttpClient(json, KeyguardHttpUserAgent.Curl)
    }

    /** The network settings this app links with. */
    val network = P2pNetwork(
        relayUrl = relay.url,
        accessToken = relay.token,
        relayStorage = relayStorage,
    )

    private val started = TimeSource.Monotonic.markNow()

    /** Failed sync passes and the statuses of the live session, for diagnostics. */
    private val log = RecentLogRepository()

    /** The AppLink worker, while it runs. */
    @Volatile
    private var worker: Job? = null

    /** Logs the statuses of the live sessions, for diagnostics; started with the first one. */
    private val statusLogger = scope.launch(start = CoroutineStart.LAZY) {
        status().filterNotNull().collect { status ->
            log.record("+${started.elapsedNow()} $status")
        }
    }

    /** Seals the authority key of the device under [MASTER_PASSWORD]. */
    private val authorityKeys = KeyguardAuthorityKeys(
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = NativeCipherEncryptor(cryptoGenerator, Base64ServiceImpl()),
    )

    /** Linked apps, as the vault session wires them. */
    private val appLink = AppLinkComponents(
        db = db,
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        authorityKeys = authorityKeys,
        settings = SqlDelightVaultSettingsKeyValueStore(
            databaseManager = db,
            dispatcher = Dispatchers.IO,
        ),
        getPurchased = PurchasedPremium,
        roomConnector = KeyguardRoomConnector(
            relayConnector = KtorKeyguardRelayConnector(httpClient),
            iceServers = KeyguardIceServers(httpClient),
            transportFactory = transportFactory,
            cryptoGenerator = cryptoGenerator,
            cipherEncryptor = NativeCipherEncryptor(cryptoGenerator, Base64ServiceImpl()),
        ),
        compressor = GzipCompressor,
        connectivityService = OnlineConnectivityService,
        logRepository = log,
        relayMailboxes = KtorKeyguardRelayMailboxes(httpClient),
        mailboxTimings = MAILBOX_TIMINGS,
        blobRetryDelayMs = BLOB_RETRY_DELAY_MS,
    )

    /** The email forwarders, as the generator screens edit them: each change is recorded for linked apps. */
    private val forwarders = GeneratorEmailRelayRepositoryImpl(
        databaseManager = db,
        json = json,
        dispatcher = Dispatchers.IO,
        recorder = appLink.recorder,
    )

    /** The wordlists, as the generator screens edit them: each change is recorded for linked apps. */
    private val wordlists = GeneratorWordlistRepositoryImpl(
        databaseManager = db,
        dispatcher = Dispatchers.IO,
        recorder = appLink.recorder,
    )

    val enrollment: KeyguardEnrollmentCoordinator get() = appLink.enrollment

    private val revokeDevice = RevokeKeyguardDeviceImpl(
        db = db,
        crypto = crypto,
        authorityKeys = authorityKeys,
        queueSync = appLink.hosts,
        windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by scope {},
    )

    /**
     * Starts the AppLink worker, as the app does in the foreground: it holds the session of
     * the link while this app is linked to other devices, with a sync pass as it starts. A
     * sync pass shares that session.
     */
    fun startLive() {
        check(worker == null) { "$name runs its AppLink worker already" }
        statusLogger.start()
        worker = AppLinkWorker(
            appLinkLauncher = appLink.sync,
            sessionLauncher = appLink.registry,
        ).launch(scope)
    }

    /** Stops the AppLink worker, as going offline or to the background does. */
    suspend fun stopLive() {
        worker?.cancelAndJoin()
        worker = null
    }

    /** `true` while this app holds the session of the link. */
    fun isLive(): Boolean = worker != null && appLink.registry.get(P2pGroupId.APP_LINK_ID) != null

    /** The status of the live session of the link, or `null` while none runs. */
    fun status(): Flow<KeyguardSessionStatus?> = appLink.registry.status(P2pGroupId.APP_LINK_ID)

    /** Queues a sync pass, as the background sync of linked apps does. */
    suspend fun sync() {
        appLink.sync.queueSync().bind()
    }

    suspend fun linkState(): DAppLinkState = appLink.service.observeState().first()

    suspend fun membership(): P2pMembership = checkNotNull(appLink.service.membership()) { "$name is not linked" }

    /** This app's id in its link. */
    suspend fun deviceId(): String = membership().device.deviceId

    /** How this app last reached [peerDeviceId], as `p2pPeerState.lastTransport` holds it. */
    suspend fun lastTransport(peerDeviceId: String): String? = read { database ->
        database.p2pPeerStateQueries
            .getByDeviceId(groupId = P2pGroupId.APP_LINK_ID, deviceId = peerDeviceId)
            .executeAsOneOrNull()
            ?.lastTransport
    }

    /** Adds an email forwarder, as the generator screen does; returns its sync id. */
    suspend fun addForwarder(
        name: String,
        type: String = DEFAULT_FORWARDER_TYPE,
        data: Map<String, String> = mapOf(API_KEY to cryptoGenerator.uuid()),
    ): String {
        val known = forwarderIds()
        forwarders.put(
            DGeneratorEmailRelay(
                name = name,
                type = type,
                data = data.toPersistentMap(),
                createdDate = Clock.System.now(),
            ),
        ).bind()
        // Other apps may add forwarders meanwhile.
        return snapshot().forwarders
            .filter { (id, forwarder) -> id !in known && forwarder.name == name }
            .keys
            .single()
    }

    /** Edits the email forwarder with sync [id], as the generator screen does. */
    suspend fun editForwarder(
        id: String,
        name: String? = null,
        data: Map<String, String>? = null,
    ) {
        val localId = forwarderLocalId(id)
        val current = forwarders.get().first().single { it.id == localId }
        forwarders.put(
            current.copy(
                name = name ?: current.name,
                data = data?.toPersistentMap() ?: current.data,
            ),
        ).bind()
    }

    suspend fun removeForwarder(id: String) {
        forwarders.removeByIds(setOf(forwarderLocalId(id))).bind()
    }

    private suspend fun forwarderLocalId(syncId: String): String = read { database ->
        database.generatorEmailRelayQueries.getBySyncId(syncId).executeAsOne().id.toString()
    }

    /** Adds a wordlist of [words], as the generator screen does. */
    suspend fun addWordlist(name: String, words: List<String>) {
        wordlists.post(name, words).bind()
    }

    /** The words of each wordlist, by name. */
    suspend fun wordlists(): Map<String, List<String>> = read { database ->
        database.generatorWordlistQueries
            .get()
            .executeAsList()
            .associate { wordlist ->
                val words = database.generatorWordlistWordQueries
                    .getPrimitiveByWordlistId(wordlist.id)
                    .executeAsList()
                wordlist.name to words
            }
    }

    /** The object key of the link this app holds, if any. */
    suspend fun objectKey(): ObjectKey? = read { database ->
        ObjectKey.fromEntry(KeyguardSyncStore(database, P2pGroupId.APP_LINK_ID).loadEntry(ObjectKey.ENTRY_KEY))
    }

    /** Removes [deviceId] from the link, as the Linked devices screen does. */
    suspend fun revoke(deviceId: String) {
        revokeDevice(P2pGroupId.AppLink, setOf(deviceId), MASTER_PASSWORD).bind()
    }

    suspend fun unlink() {
        LeaveKeyguardGroupImpl(
            db = db,
            crypto = crypto,
            authorityKeys = authorityKeys,
            dissolve = { appLink.service.dissolve() },
            retainLive = appLink.registry::retainForPublication,
        )(P2pGroupId.AppLink, MASTER_PASSWORD).bind()
    }

    private suspend fun forwarderIds(): Set<String> = read { database ->
        database.generatorEmailRelayQueries
            .getAll()
            .executeAsList()
            .map { it.syncId }
            .toSet()
    }

    /** What this app holds of the link, read in one go. */
    suspend fun snapshot(): LinkSnapshot = db.mutate(TAG) { database ->
        val groupId = P2pGroupId.APP_LINK_ID
        LinkSnapshot(
            forwarders = database.generatorEmailRelayQueries
                .getAll()
                .executeAsList()
                .associate { row ->
                    row.syncId to Forwarder(
                        name = row.name,
                        type = row.type,
                        data = (P2pJson.parseToJsonElement(row.data_) as JsonObject)
                            .mapValues { (_, value) -> (value as JsonPrimitive).content },
                    )
                },
            entries = database.p2pSyncEntryQueries
                .getByGroupId(groupId = groupId)
                .executeAsList()
                .associate { row ->
                    val entry = SyncEntry(
                        id = row.entryId,
                        kind = row.kind,
                        versions = P2pJson.decodeFromString(versionsSerializer, row.versions),
                        deletes = P2pJson.decodeFromString(deletesSerializer, row.deletes),
                    )
                    row.entryId to EntryState(
                        kind = row.kind,
                        versions = P2pJson.parseToJsonElement(row.versions),
                        deletes = P2pJson.parseToJsonElement(row.deletes),
                        deleted = entry.deleted,
                    )
                },
            roster = database.p2pGroupQueries
                .getByGroupId(groupId = groupId)
                .executeAsOneOrNull()
                ?.roster
                ?.let { P2pJson.decodeFromString(Roster.serializer(), it) }
                ?.let(::rosterStateOf)
                .orEmpty(),
            pendingEnvelopes = appLink.inbox.peek(groupId).pending(),
        )
    }.bind()

    /** What this app knows about its link and sessions, for a failure message. */
    suspend fun diagnostics(): String = buildString {
        val membership = runCatching { appLink.service.membership() }.getOrElse { it }
        appendLine("$name: membership=$membership")
        appendLine("  link: ${runCatching { linkState() }.getOrElse { it }}")
        appendLine("  live status: ${appLink.registry.get(P2pGroupId.APP_LINK_ID)?.status?.value}")
        appendLine("  pending envelopes: ${appLink.inbox.peek(P2pGroupId.APP_LINK_ID).pending()}")
        appendLine("  active devices: ${runCatching { snapshot().activeDevices() }.getOrElse { it }}")
        log.lines().forEach { appendLine("  $it") }
    }

    private suspend fun <T> read(block: (Database) -> T): T = block(db.get().bind())

    override fun close() {
        runBlocking {
            withTimeoutOrNull(CLOSE_TIMEOUT) {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
        appLink.close()
        httpClient.close()
        sqlManager.close()
        directory.deleteRecursively()
    }

    companion object {
        const val API_KEY = "apiKey"

        const val DEFAULT_FORWARDER_TYPE = "SimpleLogin"

        /**
         * Relay storage asks a peer that is offline for its state every second, and posts
         * a change shortly after it is made, so that the scenarios take seconds.
         */
        val MAILBOX_TIMINGS = KeyguardMailboxTimings(
            fetchIntervalMs = 1_000L,
            postDebounceMs = 200L,
            recheckMs = 1_000L,
            maxBackoffMs = 2_000L,
        )

        /** A blob a peer did not leave is asked for again soon. */
        private const val BLOB_RETRY_DELAY_MS = 500L

        private const val TAG = "p2p-e2e"
        private const val MASTER_KEY_LENGTH = 32
        private val CLOSE_TIMEOUT = 15.seconds

        private val versionsSerializer = ListSerializer(EntryVersion.serializer())
        private val deletesSerializer = ListSerializer(EntryDelete.serializer())
    }
}

/** The master password of every test device. */
internal const val MASTER_PASSWORD = "test master password"

/** The record and the revocations of each device of the [roster], by id. */
private fun rosterStateOf(roster: Roster): Map<String, RosterState> {
    val records = roster.devices.associateBy { it.id }
    val revocations = roster.revoked.groupBy { it.deviceId }
    return (records.keys + revocations.keys).associateWith { id ->
        RosterState(
            record = records[id]?.raw,
            revocations = revocations[id].orEmpty().toSet(),
        )
    }
}

/** How many rosters and envelopes of the batch wait: its merged roster counts once. */
private fun KeyguardInbox.Batch.pending(): Int = envelopes.size + listOfNotNull(roster).size
