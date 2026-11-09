package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.derivedGroupId
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionTimings
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.provider.bitwarden.usecase.RevokeKeyguardDeviceImpl
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKey
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkBlobs
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkSync
import com.artemchep.keyguard.provider.keyguard.applink.AppLinkTestDevice
import com.artemchep.keyguard.provider.keyguard.applink.TEST_MASTER_PASSWORD
import com.artemchep.keyguard.provider.keyguard.applink.saveAppLinkMembership
import com.artemchep.keyguard.provider.keyguard.applink.selfSignedRecord
import com.artemchep.keyguard.provider.keyguard.crypto.NativeP2pCrypto
import com.artemchep.keyguard.provider.keyguard.founderRecord
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardIceServers
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayConnector
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayMailboxes
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardTransportFactory
import com.artemchep.keyguard.provider.keyguard.transport.TestRelay
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Sessions that notice a dead peer within seconds, so tests do not wait 15 of them. */
internal val FAST_TIMINGS = SyncSessionTimings(
    heartbeatIntervalMs = 500L,
    deadAfterMs = 3_000L,
)

/** A silent peer is dead only after a minute: longer than any of the tests waits. */
internal val SLOW_DEATH = SyncSessionTimings(
    heartbeatIntervalMs = 500L,
    deadAfterMs = 60_000L,
)

/** How long a session test waits for something to happen. */
internal val SESSION_TIMEOUT = 40.seconds

private const val POLL_MS = 100L

/** No ICE servers: the tests connect over loopback, or relay forwarding. */
private val testHttpClient: HttpClient by lazy {
    HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
}

/**
 * A room connector of the [relayConnector], with relay forwarding only unless told
 * otherwise, and no ICE servers unless [iceServers] gives some.
 */
internal fun testRoomConnector(
    relayConnector: KeyguardRelayConnector,
    transportFactory: KeyguardTransportFactory = KeyguardTransportFactory.RelayOnly,
    iceServers: KeyguardIceServers = KeyguardIceServers(testHttpClient),
): KeyguardRoomConnector {
    val cryptoGenerator = NativeCryptoGenerator()
    return KeyguardRoomConnector(
        relayConnector = relayConnector,
        iceServers = iceServers,
        transportFactory = transportFactory,
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = NativeCipherEncryptor(cryptoGenerator, Base64ServiceImpl()),
    )
}

/** TEST ONLY: seals authority keys under [TEST_MASTER_PASSWORD]. */
internal val testAuthorityKeys: KeyguardAuthorityKeys by lazy {
    val cryptoGenerator = NativeCryptoGenerator()
    KeyguardAuthorityKeys(cryptoGenerator, NativeCipherEncryptor(cryptoGenerator, Base64ServiceImpl()))
}

/** TEST ONLY: opens the authority key of the [device] with [TEST_MASTER_PASSWORD]. */
internal fun openTestAuthority(device: P2pDevice): KeyguardAuthorityKey = runBlocking {
    checkNotNull(testAuthorityKeys.open(checkNotNull(device.authority), TEST_MASTER_PASSWORD).bind())
}

/** Creates a new identity of a device of a sync group, with real keys. */
internal fun newTestDevice(label: String): P2pDevice {
    val noise = NativeCryptoNoise.generateKeyPair()
    val kem = NativeCryptoPrimitives.mlKemGenerate()
    val sign = NativeCryptoPrimitives.ed25519Generate()
    val authority = runBlocking { testAuthorityKeys.create(TEST_MASTER_PASSWORD).bind() }
        .use { it.authority }
    return P2pDevice(
        deviceId = Uuid.random().toString(),
        label = label,
        platform = "desktop",
        noisePublicKeyBase64 = P2pEncoding.base64Url(noise.publicKey),
        noisePrivateKeyBase64 = P2pEncoding.base64Url(noise.privateKey),
        kemPublicKeyBase64 = P2pEncoding.base64Url(kem.publicKey),
        kemPrivateKeyBase64 = P2pEncoding.base64Url(kem.privateKey),
        signPublicKeyBase64 = P2pEncoding.base64Url(sign.publicKey),
        signPrivateKeyBase64 = P2pEncoding.base64Url(sign.privateKey),
        authority = authority,
    )
}

/**
 * TEST ONLY: linked apps on [size] devices, each with its own in-memory database, all
 * reaching each other through one [relay]. The first device founded the link and
 * admitted the others. With [relayStorage], every device allows relay storage from the
 * start, in its settings and its roster record. With [objectKey], every device holds the
 * object key of the founder, as a joiner gets it in its bundle.
 */
internal class KeyguardSessionTestGroup(
    size: Int,
    val relay: TestRelay = TestRelay(),
    timings: SyncSessionTimings = FAST_TIMINGS,
    exchangeDeadlineMs: Long = KeyguardSessionRegistry.EXCHANGE_DEADLINE_MS,
    maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
    transportFactory: KeyguardTransportFactory = KeyguardTransportFactory.RelayOnly,
    /** The identities of the devices, the founder first. */
    private val identities: List<P2pDevice> = List(size) { newTestDevice("Device $it") },
    /** The ICE servers of each device. */
    iceServers: () -> KeyguardIceServers = { KeyguardIceServers(testHttpClient) },
    relayStorage: Boolean = false,
    mailboxTimings: KeyguardMailboxTimings = KeyguardMailboxTimings(),
    blobRetryDelayMs: Long = AppLinkBlobs.PEER_RETRY_DELAY_MS,
    /** The caps every record starts with; a device lists its own once it syncs. */
    caps: DeviceCaps = DeviceCaps.current(relayStorage),
    objectKey: Boolean = true,
) : AutoCloseable {
    val crypto = NativeP2pCrypto(NativeCryptoGenerator())

    val groupKeyBase64: String = P2pEncoding.base64Url(NativeCryptoGenerator().seed(length = 32))

    /** The roster every device starts from. */
    val roster: Roster = run {
        val founder = identities.first()
        val records = openTestAuthority(founder).use { authority ->
            val founderAddedAt = Hlc(wall = START_TIME, counter = 0, node = founder.deviceId)
            val founderRecord = founderRecord(crypto, founder, founderAddedAt, authority, caps)
            val signer = RosterSigner(crypto, founderRecord.derivedGroupId(crypto::sha256))
            listOf(founderRecord) + identities.drop(1).mapIndexed { index, device ->
                val addedAt = Hlc(wall = START_TIME + index + 1, counter = 0, node = founder.deviceId)
                val record = signer.selfSignedRecord(device, addedAt, addedBy = founder.deviceId, caps = caps)
                signer.admit(record, authority.seed)
            }
        }
        Roster(founder = founder.deviceId, devices = records).normalized()
    }

    /** The id of the group: it derives from the founder. */
    val groupId: String = roster.devices
        .single { it.id == roster.founder }
        .derivedGroupId(crypto::sha256)

    val devices: List<SessionTestDevice> = identities.map { identity ->
        val membership = P2pMembership(
            groupId = groupId,
            groupKeyBase64 = groupKeyBase64,
            founderId = identities.first().deviceId,
            device = identity,
            network = P2pNetwork(relayUrl = "wss://relay.test", relayStorage = relayStorage),
        )
        SessionTestDevice(
            membership = membership,
            roster = roster,
            roomConnector = testRoomConnector(relay.connector, transportFactory, iceServers()),
            timings = timings,
            exchangeDeadlineMs = exchangeDeadlineMs,
            maxEnvelopeSize = maxEnvelopeSize,
            relayMailboxes = relay.mailboxes,
            mailboxTimings = mailboxTimings,
            blobRetryDelayMs = blobRetryDelayMs,
        )
    }

    init {
        if (objectKey) seedObjectKey()
    }

    /** Hands every device a new object key of the founder. */
    private fun seedObjectKey() {
        val founder = devices.first()
        val key = ObjectKey(NativeCryptoGenerator().seed(length = ObjectKey.KEY_BYTES), covers = emptyList())
        val store = KeyguardSyncStore(founder.db, P2pGroupId.APP_LINK_ID)
        val entry = LocalEditPublisher().publish(
            id = ObjectKey.ENTRY_ID,
            kind = EntryKind.OBJECT_KEY,
            current = null,
            applied = null,
            local = key.toBody(),
            clock = store.clock(founder.deviceId) { Clock.System.now() },
            signer = EntrySigner(crypto, groupId, founder.membership.device.signPrivateKey()),
            budget = EntryBudget.of(emptyList()),
        )
        devices.forEach { device ->
            KeyguardSyncStore(device.db, P2pGroupId.APP_LINK_ID).saveEntry(entry)
        }
    }

    /**
     * Removes [device] from the link on the founder, as its user does: a local change that
     * queues a sync pass of the founder.
     */
    suspend fun revoke(device: SessionTestDevice) {
        val founder = devices.first()
        val revoke = RevokeKeyguardDeviceImpl(
            db = founder.dbManager,
            crypto = founder.crypto,
            authorityKeys = founder.app.authorityKeys,
            queueSync = founder.app.components.hosts,
            windowCoroutineScope = founder.windowScope,
        )
        revoke(P2pGroupId.AppLink, setOf(device.deviceId), TEST_MASTER_PASSWORD).bind()
    }

    override fun close() {
        devices.forEach { it.close() }
    }

    companion object {
        const val START_TIME: Long = 1_700_000_000_000L
    }
}

/**
 * TEST ONLY: one device of a [KeyguardSessionTestGroup]: an app linked to the others, its
 * sync pass, and its session registry. A queued sync runs the pass, one at a time.
 */
// Test wiring: the actions of a whole device.
@Suppress("TooManyFunctions")
internal class SessionTestDevice(
    val membership: P2pMembership,
    roster: Roster,
    roomConnector: KeyguardRoomConnector,
    timings: SyncSessionTimings,
    exchangeDeadlineMs: Long,
    maxEnvelopeSize: Int,
    relayMailboxes: KeyguardRelayMailboxes = KeyguardRelayMailboxes.NONE,
    mailboxTimings: KeyguardMailboxTimings = KeyguardMailboxTimings(),
    blobRetryDelayMs: Long = AppLinkBlobs.PEER_RETRY_DELAY_MS,
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val windowScope: WindowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by scope {}

    /** The app on this device; a queued sync of its link runs [sync]. */
    val app = AppLinkTestDevice(
        name = membership.device.label,
        realTime = true,
        roomConnector = roomConnector,
        sessionTimings = timings,
        maxEnvelopeSize = maxEnvelopeSize,
        exchangeDeadlineMs = exchangeDeadlineMs,
        onQueueSync = { scope.launch { syncQueued() } },
        relayMailboxes = relayMailboxes,
        mailboxTimings = mailboxTimings,
        blobRetryDelayMs = blobRetryDelayMs,
    )

    val deviceId: String get() = membership.device.deviceId

    val db: Database get() = app.db

    val dbManager: VaultDatabaseManager get() = app.dbManager

    val crypto: NativeP2pCrypto get() = app.crypto

    var online: Boolean by app::online

    val registry: KeyguardSessionRegistry get() = app.components.registry

    private val syncMutex = Mutex()

    init {
        db.saveAppLinkMembership(membership)
        KeyguardSyncStore(db, P2pGroupId.APP_LINK_ID).saveRoster(roster)
    }

    /**
     * Runs a sync pass of the link, see [AppLinkSync]: merges what the other devices sent,
     * exchanges with the ones that are online, and merges what arrived meanwhile. Returns
     * `false` if the pass had no one to exchange with.
     */
    suspend fun sync(): Boolean = syncMutex.withLock {
        app.components.sync.pass()
    }

    /** Runs a queued pass: as in the app, a failed pass is only logged, and the next one tries again. */
    private suspend fun syncQueued() {
        runCatching { sync() }.onFailure { e ->
            if (e is CancellationException) throw e
            System.err.println("A queued sync pass of ${membership.device.label} failed: $e")
        }
    }

    /** Holds the session, as the app does in the foreground; cancelling the job lets it go. */
    fun startLive(): Job = scope.launch { registry.hold(P2pGroupId.APP_LINK_ID, membership) }

    /**
     * Runs a session no one leases, as a killed app's: cancelling the job drops the
     * connection without a word, and what arrives meanwhile waits in the inbox.
     */
    fun startBareSession(): Job = scope.launch {
        KeyguardSessionManager(
            groupId = P2pGroupId.APP_LINK_ID,
            membership = membership,
            dependencies = app.components.sessionDependencies,
            live = MutableStateFlow(false),
        ).run()
    }

    /** The status of the link, or `null` while no session runs. */
    fun status() = registry.status(P2pGroupId.APP_LINK_ID)

    /** The state of the link on this device. */
    suspend fun state(): DAppLinkState = app.service.observeState().first()

    /** The stored roster of the link, validated. */
    fun roster(): Roster {
        val stored = KeyguardSyncStore(db, P2pGroupId.APP_LINK_ID).loadRoster(membership.founderId)
        return RosterValidator(crypto, membership.groupId).validate(stored)
    }

    suspend fun addForwarder(name: String) = app.addForwarder(name)

    suspend fun renameForwarder(from: String, to: String) = app.editForwarder(app.forwarder(from), name = to)

    /** The names of the email forwarders on this device. */
    suspend fun forwarders(): Set<String> = app.forwarderList().map { it.name }.toSet()

    suspend fun addWordlist(name: String, words: List<String>) = app.addWordlist(name, words)

    suspend fun renameWordlist(from: String, to: String) = app.renameWordlist(from, to)

    suspend fun removeWordlist(name: String) = app.removeWordlist(name)

    /** The words of each wordlist on this device, by name. */
    suspend fun wordlists(): Map<String, List<String>> = app.wordlistWords()

    override fun close() {
        app.close()
        scope.cancel()
    }
}

/** TEST ONLY: an in-memory vault database whose changes run one at a time, as the real one's do. */
internal class SerializedTestDatabaseManager(
    private val database: Database,
) : VaultDatabaseManager {
    private val mutex = Mutex()

    /** The tag of the next change to fail, and how, see [failNext]. */
    private var failure: Pair<String, Throwable>? = null

    /** Makes the next change of the [tag] fail with [error] before it writes anything. */
    fun failNext(tag: String, error: Throwable) {
        failure = tag to error
    }

    override fun get() = io(database)

    override fun <T> mutate(
        tag: String,
        block: suspend (Database) -> T,
    ) = ioEffect(Dispatchers.IO) {
        mutex.withLock {
            val error = failure?.takeIf { it.first == tag }?.second
            if (error != null) {
                failure = null
                throw error
            }
            block(database)
        }
    }

    override fun changePassword(newMasterKey: MasterKey) = io(Unit)
}

/** The peers that hold the local state. */
internal fun KeyguardSessionStatus.upToDateCount(): Int = peers.values
    .count { it is DKeyguardPeerStatus.Connection.Connected && it.upToDate }

/** Waits until the live status of the [device] satisfies [predicate]. */
internal suspend fun awaitStatus(
    device: SessionTestDevice,
    predicate: (KeyguardSessionStatus) -> Boolean,
) {
    withTimeout(SESSION_TIMEOUT) {
        device.status().filterNotNull().first(predicate)
    }
}

/** Waits until the live sessions of [a] and [b] are connected to each other. */
internal suspend fun awaitConnected(a: SessionTestDevice, b: SessionTestDevice) {
    awaitStatus(a) { status -> status.peers[b.deviceId] is DKeyguardPeerStatus.Connection.Connected }
    awaitStatus(b) { status -> status.peers[a.deviceId] is DKeyguardPeerStatus.Connection.Connected }
}

/** Waits until [predicate] holds. */
internal suspend fun eventually(predicate: suspend () -> Boolean) {
    withTimeout(SESSION_TIMEOUT) {
        while (!predicate()) delay(POLL_MS)
    }
}

/** `runTest` skips delays; sessions need the real clock. */
internal fun realTime(block: suspend CoroutineScope.() -> Unit) =
    runTest(timeout = 120.seconds) { withContext(Dispatchers.Default) { block() } }
