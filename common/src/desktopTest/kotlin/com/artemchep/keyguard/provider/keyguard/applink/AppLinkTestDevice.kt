package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.database.exposed.TestExposedDatabaseManager
import com.artemchep.keyguard.common.service.keyvalue.impl.SqlDelightVaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepositoryImpl
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepositoryImpl
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistRepositoryImpl
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.provider.bitwarden.usecase.RevokeKeyguardDeviceImpl
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentTimings
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionTimings
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.crypto.NativeP2pCrypto
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentCoordinator
import com.artemchep.keyguard.provider.keyguard.session.KeyguardMailboxTimings
import com.artemchep.keyguard.provider.keyguard.session.KeyguardRoomConnector
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionRegistry
import com.artemchep.keyguard.provider.keyguard.session.SerializedTestDatabaseManager
import com.artemchep.keyguard.provider.keyguard.session.testRoomConnector
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import com.artemchep.keyguard.provider.keyguard.toRosterDevice
import com.artemchep.keyguard.provider.keyguard.transport.GzipCompressor
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayMailboxes
import com.artemchep.keyguard.provider.keyguard.transport.TestRelay
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * One app on an in-memory database, with the email forwarders, wordlists, URL overrides,
 * blocked URIs and linked apps wired as the vault session wires them, see
 * [AppLinkComponents]. A queued sync pass only calls [onQueueSync]: envelopes move between
 * devices by hand, or by the passes of a test.
 */
// One function per thing a test does on the device; every knob has a default.
@Suppress("TooManyFunctions", "LongParameterList")
internal class AppLinkTestDevice(
    val name: String,
    premium: Premium = Premium.PURCHASED,
    /** `true` to stamp with the real clock instead of [time]. */
    realTime: Boolean = false,
    /** How the device reaches the others; by default, a relay no one else is on. */
    roomConnector: KeyguardRoomConnector = testRoomConnector(TestRelay().connector),
    sessionTimings: SyncSessionTimings = SyncSessionTimings(),
    maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
    exchangeDeadlineMs: Long = KeyguardSessionRegistry.EXCHANGE_DEADLINE_MS,
    enrollmentTimings: EnrollmentTimings = KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS,
    /** Called whenever a sync pass of the link is queued. */
    onQueueSync: () -> Unit = {},
    /** The most words of wordlists a sync pass writes. */
    maxWordsPerPass: Int = WordlistBlob.MAX_WORDS,
    /** The sessions a sync pass asks for resends; by default the device's own registry. */
    sessionLauncher: KeyguardSessionLauncher? = null,
    /** The items the relay keeps for the devices that are offline; by default none. */
    relayMailboxes: KeyguardRelayMailboxes = KeyguardRelayMailboxes.NONE,
    mailboxTimings: KeyguardMailboxTimings = KeyguardMailboxTimings(),
    /** How long a device that could not hand a blob over is passed over for it. */
    blobRetryDelayMs: Long = AppLinkBlobs.PEER_RETRY_DELAY_MS,
) : AutoCloseable {
    val premium = MutableStateFlow(premium)

    /** The physical time of the device, in milliseconds, unless it runs on the real clock. */
    var time: Long = START_TIME

    val db: Database = createUploadTestDatabase()

    /** The database changes run one at a time, as the real ones do. */
    val dbManager = SerializedTestDatabaseManager(db)

    private val cryptoGenerator = NativeCryptoGenerator()

    val crypto = NativeP2pCrypto(cryptoGenerator)

    /** Seals the authority keys of the device under [TEST_MASTER_PASSWORD]. */
    val authorityKeys = KeyguardAuthorityKeys(
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = NativeCipherEncryptor(cryptoGenerator, Base64ServiceImpl()),
    )

    /** How far the clock of the device runs ahead of the real one, if it runs on it. */
    @Volatile
    var clockOffsetMs: Long = 0L

    private val now: () -> Instant = if (realTime) {
        { Clock.System.now() + clockOffsetMs.milliseconds }
    } else {
        { Instant.fromEpochMilliseconds(time) }
    }

    private val onlineState = MutableStateFlow(true)

    /** `false` to cut the device off the network. */
    var online: Boolean
        get() = onlineState.value
        set(value) {
            onlineState.value = value
        }

    val components = AppLinkComponents(
        db = dbManager,
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        authorityKeys = authorityKeys,
        settings = SqlDelightVaultSettingsKeyValueStore(
            databaseManager = dbManager,
            dispatcher = Dispatchers.Unconfined,
        ),
        getPurchased = object : GetPurchased {
            override fun invoke() = this@AppLinkTestDevice.premium
        },
        roomConnector = roomConnector,
        compressor = GzipCompressor,
        connectivityService = object : ConnectivityService {
            override val availableFlow = onlineState
                .filter { it }
                .map { }

            override fun isInternetAvailable(): Boolean = online
        },
        logRepository = LogRepositoryBridge(emptyList()),
        sessionTimings = sessionTimings,
        maxEnvelopeSize = maxEnvelopeSize,
        exchangeDeadlineMs = exchangeDeadlineMs,
        enrollmentTimings = enrollmentTimings,
        queueSync = { ioEffect { onQueueSync() } },
        sessionLauncher = sessionLauncher,
        now = now,
        maxWordsPerPass = maxWordsPerPass,
        relayMailboxes = relayMailboxes,
        mailboxTimings = mailboxTimings,
        blobRetryDelayMs = blobRetryDelayMs,
    )

    val service: AppLinkService get() = components.service

    val switches: AppSyncSwitches get() = components.switches

    private val forwarders = GeneratorEmailRelayRepositoryImpl(
        databaseManager = dbManager,
        json = Json,
        dispatcher = Dispatchers.Unconfined,
        recorder = components.recorder,
    )

    private val wordlists = GeneratorWordlistRepositoryImpl(
        databaseManager = dbManager,
        dispatcher = Dispatchers.Unconfined,
        recorder = components.recorder,
    )

    private val urlOverrides = UrlOverrideRepositoryImpl(
        databaseManager = dbManager,
        dispatcher = Dispatchers.Unconfined,
        recorder = components.recorder,
    )

    /** The exposed database of the device, which autofill reads while the vault is locked. */
    private val exposedDbManager = TestExposedDatabaseManager()

    private val urlBlocks = UrlBlockRepositoryImpl(
        vaultDatabaseManager = dbManager,
        exposedDatabaseManager = exposedDbManager,
        dispatcher = Dispatchers.Unconfined,
        recorder = components.recorder,
    )

    /** Starts a link here, as the device [name]. */
    suspend fun link() {
        service.prepareLink(name, NETWORK, TEST_MASTER_PASSWORD)
    }

    /** Creates a new device named [label] that joins a link, see [join]. */
    suspend fun newDevice(label: String = name): P2pDevice = authorityKeys
        .create(TEST_MASTER_PASSWORD)
        .bind()
        .use { components.deviceFactory.create(label = label, authority = it.authority).bind() }

    /** Joins the link of the [member] as a new device: the member admits it, this device imports the bundle. */
    suspend fun join(member: AppLinkTestDevice) {
        service.checkJoinable()
        val device = newDevice()
        val bundle = member.admit(device)
        service.import(bundle, device, NETWORK)
    }

    /**
     * Admits the [joiner] to the link of this device, as pairing does, and stores the
     * admission, as the acknowledgement of the joiner does, if [save].
     */
    suspend fun admit(joiner: P2pDevice, save: Boolean = true): EnrollmentBundle {
        val vault = components.enrollmentVault
        val member = vault.member(P2pGroupId.AppLink)
        // Admitted after the member was.
        time = maxOf(time, member.record.addedAt.wall + 1)
        val addedAt = Hlc(wall = time, counter = 0, node = member.record.id)
        val signer = RosterSigner(crypto, member.membership.groupId)
        val record = signer.selfSignedRecord(joiner, addedAt, addedBy = member.record.id)
        val admission = vault.openAuthority(member, TEST_MASTER_PASSWORD).use { authority ->
            vault.admit(member, record, authority)
        }
        // The joiner acknowledged the bundle.
        if (save) vault.saveAdmission(member, admission)
        return admission.bundle
    }

    /** The roster this device sends, with [count] more devices it admitted. */
    suspend fun rosterWithMore(count: Int): Roster {
        val vault = components.enrollmentVault
        val member = vault.member(P2pGroupId.AppLink)
        val authority = requireNotNull(member.membership.device.authority)
        val signer = RosterSigner(crypto, member.membership.groupId)
        val records = vault.openAuthority(member, TEST_MASTER_PASSWORD).use { key ->
            List(count) { index ->
                val device = components.deviceFactory.create(label = "Device $index", authority = authority).bind()
                val addedAt = Hlc(wall = member.record.addedAt.wall + 1 + index, counter = 0, node = member.record.id)
                signer.admit(signer.selfSignedRecord(device, addedAt, addedBy = member.record.id), key.seed)
            }
        }
        val roster = localEnvelope().roster
        return roster.copy(devices = roster.devices + records)
    }

    /** The roster and the envelope this device sends to the others. */
    suspend fun localEnvelope(): LocalEnvelope =
        components.sessionStore.localEnvelope(P2pGroupId.APP_LINK_ID, membership(), crypto::sha256)

    /** The envelope this device sends to the others. */
    suspend fun envelope(): Envelope = localEnvelope().envelope

    /** The id of this device in its link. */
    val deviceId: String get() = membership().device.deviceId

    /**
     * Queues the roster of [from] and the [envelope] it sends, as its session hands them
     * over, without a sync pass.
     */
    suspend fun post(from: AppLinkTestDevice, envelope: Envelope? = null) {
        val local = from.localEnvelope()
        components.inbox.postRoster(P2pGroupId.APP_LINK_ID, from.deviceId, local.roster)
        components.inbox.post(P2pGroupId.APP_LINK_ID, from.deviceId, envelope ?: local.envelope)
    }

    /**
     * Merges the roster of [from], then the [envelope] it sends, as a sync pass does.
     * Returns `true` if a record did not fit the pass.
     */
    suspend fun receive(from: AppLinkTestDevice, envelope: Envelope? = null): Boolean {
        post(from, envelope)
        return components.sync.materialize(membership()).deferred
    }

    /** Runs the merge of a sync pass. Returns `true` if a record did not fit it. */
    suspend fun materialize(): Boolean = components.sync.materialize(membership()).deferred

    /** Removes the [device] from the link, as its user does here. */
    suspend fun revoke(device: AppLinkTestDevice) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val revoke = RevokeKeyguardDeviceImpl(
            db = dbManager,
            crypto = crypto,
            authorityKeys = authorityKeys,
            queueSync = components.hosts,
            windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by scope {},
            now = now,
        )
        revoke(P2pGroupId.AppLink, setOf(device.membership().device.deviceId), TEST_MASTER_PASSWORD).bind()
    }

    /** Sends the state of this device to [other], then receives the state of [other]. */
    suspend fun exchangeWith(other: AppLinkTestDevice) {
        other.receive(this)
        receive(other)
    }

    fun membership(): P2pMembership = requireNotNull(db.appLinkMembershipOrNull()) {
        "$name is not linked."
    }

    /** The stored entries of the link, by kind and id. */
    fun entries(): Map<EntryKey, SyncEntry> = KeyguardSyncStore(db, P2pGroupId.APP_LINK_ID).loadEntries()

    /** The stored entries of the link of the [kind]. */
    fun entriesOf(kind: EntryKind): List<SyncEntry> = entries().values.filter { it.knownKind == kind }

    suspend fun addForwarder(
        name: String,
        type: String = "duckduckgo",
        vararg data: Pair<String, String>,
    ) = putForwarder(
        DGeneratorEmailRelay(
            name = name,
            type = type,
            data = data.toMap().toPersistentMap(),
            createdDate = now(),
        ),
    )

    suspend fun editForwarder(
        current: DGeneratorEmailRelay,
        name: String = current.name,
        data: Map<String, String> = current.data,
    ) = putForwarder(
        current.copy(
            name = name,
            data = data.toPersistentMap(),
            createdDate = now(),
        ),
    )

    // One at a time with the sync passes: the test database shares one transaction.
    private suspend fun putForwarder(model: DGeneratorEmailRelay) {
        dbManager.mutate("test") { forwarders.put(model).bind() }.bind()
    }

    suspend fun removeForwarder(current: DGeneratorEmailRelay) {
        dbManager.mutate("test") { forwarders.removeByIds(setOfNotNull(current.id)).bind() }.bind()
    }

    suspend fun forwarderList(): List<DGeneratorEmailRelay> = forwarders.get().first()

    suspend fun forwarder(name: String): DGeneratorEmailRelay = forwarderList().single { it.name == name }

    suspend fun forwarderSyncId(name: String): String {
        val id = requireNotNull(forwarder(name).id).toLong()
        return db.generatorEmailRelayQueries.getById(id).executeAsOne().syncId
    }

    suspend fun addWordlist(name: String, words: List<String>) {
        dbManager.mutate("test") { wordlists.post(name, words).bind() }.bind()
    }

    suspend fun renameWordlist(from: String, to: String) {
        val id = wordlist(from).idRaw
        dbManager.mutate("test") { wordlists.patch(id, to).bind() }.bind()
    }

    suspend fun removeWordlist(name: String) {
        val id = wordlist(name).idRaw
        dbManager.mutate("test") { wordlists.removeByIds(setOf(id)).bind() }.bind()
    }

    suspend fun wordlistList(): List<DGeneratorWordlist> = wordlists.get().first()

    suspend fun wordlist(name: String): DGeneratorWordlist = wordlistList().single { it.name == name }

    /** The words of each wordlist, by name. */
    suspend fun wordlistWords(): Map<String, List<String>> = wordlistList().associate { wordlist ->
        val words = db.generatorWordlistWordQueries
            .getPrimitiveByWordlistId(wordlist.idRaw)
            .executeAsList()
        wordlist.name to words
    }

    suspend fun wordlistSyncId(name: String): String = db.generatorWordlistQueries
        .getById(wordlist(name).idRaw)
        .executeAsOne()
        .syncId

    suspend fun addUrlOverride(
        name: String,
        regex: String = "^https://example\\.com/.*",
        command: String = "https://example.org/{url:rmvscm}",
        enabled: Boolean = true,
    ) = putUrlOverride(
        DGlobalUrlOverride(
            name = name,
            regex = regex.toRegex(),
            command = command,
            createdDate = now(),
            enabled = enabled,
        ),
    )

    suspend fun editUrlOverride(
        current: DGlobalUrlOverride,
        name: String = current.name,
        enabled: Boolean = current.enabled,
    ) = putUrlOverride(
        current.copy(
            name = name,
            enabled = enabled,
            createdDate = now(),
        ),
    )

    private suspend fun putUrlOverride(model: DGlobalUrlOverride) {
        dbManager.mutate("test") { urlOverrides.put(model).bind() }.bind()
    }

    suspend fun removeUrlOverride(current: DGlobalUrlOverride) {
        dbManager.mutate("test") { urlOverrides.removeByIds(setOfNotNull(current.id)).bind() }.bind()
    }

    suspend fun urlOverrideList(): List<DGlobalUrlOverride> = urlOverrides.get().first()

    suspend fun urlOverride(name: String): DGlobalUrlOverride = urlOverrideList().single { it.name == name }

    /** The sync id of the URL override [name]; `null` for the built-in one. */
    suspend fun urlOverrideSyncId(name: String): String? {
        val id = requireNotNull(urlOverride(name).id).toLong()
        return db.urlOverrideQueries.getById(id).executeAsOne().syncId
    }

    suspend fun addUrlBlock(
        name: String,
        description: String = "",
        uri: String = "example.com",
        mode: MatchDetection = MatchDetection.Domain,
        enabled: Boolean = true,
        exposed: Boolean = true,
    ) = putUrlBlock(
        DGlobalUrlBlock(
            name = name,
            description = description,
            uri = uri,
            mode = mode,
            createdDate = now(),
            enabled = enabled,
            exposed = exposed,
        ),
    )

    suspend fun editUrlBlock(
        current: DGlobalUrlBlock,
        name: String = current.name,
        description: String = current.description,
        uri: String = current.uri,
        mode: MatchDetection = current.mode,
        enabled: Boolean = current.enabled,
        exposed: Boolean = current.exposed,
    ) = putUrlBlock(
        current.copy(
            name = name,
            description = description,
            uri = uri,
            mode = mode,
            enabled = enabled,
            exposed = exposed,
        ),
    )

    private suspend fun putUrlBlock(model: DGlobalUrlBlock) {
        dbManager.mutate("test") { urlBlocks.put(model).bind() }.bind()
    }

    suspend fun removeUrlBlock(current: DGlobalUrlBlock) {
        dbManager.mutate("test") { urlBlocks.removeByIds(setOfNotNull(current.id)).bind() }.bind()
    }

    suspend fun urlBlockList(): List<DGlobalUrlBlock> = urlBlocks.get().first()

    suspend fun urlBlock(name: String): DGlobalUrlBlock = urlBlockList().single { it.name == name }

    suspend fun urlBlockSyncId(name: String): String {
        val id = requireNotNull(urlBlock(name).id).toLong()
        return db.urlBlockQueries.getById(id).executeAsOne().syncId
    }

    /** The names of the blocked URIs the exposed database holds, by their local ids. */
    fun exposedUrlBlocks(): Map<String, String> = exposedDbManager.database
        .urlBlockQueries
        .get(Long.MAX_VALUE)
        .executeAsList()
        .associate { it.id.toString() to it.name }

    override fun close() {
        components.close()
    }

    companion object {
        const val START_TIME: Long = 1_700_000_000_000L

        /** The network of a link; relay storage stays off unless a test turns it on. */
        val NETWORK = P2pNetwork(relayStorage = false)
    }
}

/** TEST ONLY: the master password of every test device. */
internal const val TEST_MASTER_PASSWORD = "test master password"

/**
 * TEST ONLY: links two new devices: runs [setUp] on them, then A starts the link and
 * B joins it.
 */
internal suspend fun linkedPair(
    setUp: suspend PairScope.() -> Unit,
): Pair<AppLinkTestDevice, AppLinkTestDevice> {
    val a = AppLinkTestDevice("A")
    val b = AppLinkTestDevice("B")
    PairScope(a, b).setUp()
    a.link()
    b.time = a.time + 1
    b.join(a)
    return a to b
}

/** TEST ONLY: the devices of [linkedPair], before they link. */
internal class PairScope(
    val a: AppLinkTestDevice,
    val b: AppLinkTestDevice,
)

/** TEST ONLY: a [P2pCrypto] that counts the signatures it checks. */
internal class CountingCrypto(
    private val crypto: P2pCrypto,
) : P2pCrypto by crypto {
    var verifies = 0

    override fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        verifies += 1
        return crypto.ed25519Verify(publicKey, data, signature)
    }
}

/**
 * TEST ONLY: the new roster record of the [device], admitted by [addedBy] at [addedAt] and
 * signed by the device itself; its inviter signs the admission.
 */
internal fun RosterSigner.selfSignedRecord(
    device: P2pDevice,
    addedAt: Hlc,
    addedBy: String,
    caps: DeviceCaps = DeviceCaps.CURRENT,
): RosterDevice = selfSign(
    device.toRosterDevice(addedAt = addedAt, addedBy = addedBy, caps = caps),
    device.signPrivateKey(),
)
