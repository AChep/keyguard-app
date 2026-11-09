package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.appsync.AppSyncRecorder
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentTimings
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionTimings
import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import com.artemchep.keyguard.provider.keyguard.KeyguardAuthorityKeys
import com.artemchep.keyguard.provider.keyguard.KeyguardDeviceFactory
import com.artemchep.keyguard.provider.keyguard.KeyguardRosterAlerts
import com.artemchep.keyguard.provider.keyguard.P2pGroupHosts
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentCoordinator
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardEnrollmentVault
import com.artemchep.keyguard.provider.keyguard.session.KeyguardMailboxTimings
import com.artemchep.keyguard.provider.keyguard.session.KeyguardRoomConnector
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionDependencies
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionRegistry
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionStore
import com.artemchep.keyguard.provider.keyguard.session.VaultKeyguardRelayStorageUse
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardInbox
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayMailboxes
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Linked apps of one vault session, wired once: the link, its sync passes, the sessions
 * of the sync groups and pairing. The vault session hands the pieces out, see
 * `VaultSyncModule`. [close] it with the session.
 *
 * The sessions and the sync passes depend on each other, so each reaches the other only
 * once it runs. The [registry] and the [sync] own coroutines: they are created on first
 * use.
 *
 * @param queueSync queues a sync pass of the link; by default the [sync] runs it.
 * @param sessionLauncher the sessions a sync pass asks to request the state of their peers
 *   again; by default the [registry].
 * @param relayMailboxes the items each relay keeps for the devices that are offline.
 * @param blobRetryDelayMs how long a device that could not hand a blob over is passed over
 *   for it, see [AppLinkBlobs].
 */
// A holder of what linked apps need.
@Suppress("LongParameterList")
internal class AppLinkComponents(
    db: VaultDatabaseManager,
    crypto: P2pCrypto,
    cryptoGenerator: CryptoGenerator,
    val authorityKeys: KeyguardAuthorityKeys,
    settings: VaultSettingsKeyValueStore,
    getPurchased: GetPurchased,
    roomConnector: KeyguardRoomConnector,
    compressor: Compressor,
    connectivityService: ConnectivityService,
    logRepository: LogRepository,
    rosterAlerts: KeyguardRosterAlerts = KeyguardRosterAlerts.NONE,
    sessionTimings: SyncSessionTimings = SyncSessionTimings(),
    maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
    exchangeDeadlineMs: Long = KeyguardSessionRegistry.EXCHANGE_DEADLINE_MS,
    enrollmentTimings: EnrollmentTimings = KeyguardEnrollmentCoordinator.DEFAULT_TIMINGS,
    queueSync: (() -> IO<Unit>)? = null,
    sessionLauncher: KeyguardSessionLauncher? = null,
    now: () -> Instant = { Clock.System.now() },
    maxWordsPerPass: Int = WordlistBlob.MAX_WORDS,
    relayMailboxes: KeyguardRelayMailboxes = KeyguardRelayMailboxes.NONE,
    mailboxTimings: KeyguardMailboxTimings = KeyguardMailboxTimings(),
    blobRetryDelayMs: Long = AppLinkBlobs.PEER_RETRY_DELAY_MS,
) : AutoCloseable {
    /** The blobs that arrived and no record holds yet. */
    val receivedBlobs = AppLinkReceivedBlobs()

    private val categories = AppSyncCategories.default(
        hashOf = { bytes -> BlobExchange.hashOf(crypto, bytes) },
        received = receivedBlobs,
    )

    val switches = AppSyncSwitches(
        store = settings,
    )

    /** The name and the network settings of this device, for while it is not linked. */
    val deviceSettings = AppLinkDeviceSettings(
        store = settings,
    )

    /** The blobs the records hold, such as the words of the wordlists. */
    val blobs = AppLinkBlobs(
        db = db,
        categories = categories,
        switches = switches,
        received = receivedBlobs,
        queueSync = queueSync ?: { sync.queueSync() },
        retryDelayMs = blobRetryDelayMs,
    )

    /** Records each change of the app's own records, as it is made. */
    val recorder: AppSyncRecorder = AppSyncRecorderImpl(
        categories = categories,
        crypto = crypto,
        logRepository = logRepository,
        now = now,
    )

    val deviceFactory = KeyguardDeviceFactory(
        cryptoGenerator = cryptoGenerator,
    )

    val service = AppLinkService(
        db = db,
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        deviceFactory = deviceFactory,
        authorityKeys = authorityKeys,
        categories = categories,
        switches = switches,
        deviceSettings = deviceSettings,
        getPurchased = getPurchased,
        now = now,
    )

    private val host = AppLinkHost(
        db = db,
        categories = categories,
        switches = switches,
        service = service,
        blobs = blobs,
        queue = queueSync ?: { sync.queueSync() },
    )

    /** One host per scope of sync groups. */
    val hosts = P2pGroupHosts(
        db = db,
        hosts = listOf(host),
    )

    val inbox = KeyguardInbox()

    val sessionStore = KeyguardSessionStore(
        db = db,
        hosts = hosts,
    )

    val sessionDependencies = KeyguardSessionDependencies(
        roomConnector = roomConnector,
        store = sessionStore,
        compressor = compressor,
        crypto = crypto,
        inbox = inbox,
        queueSync = { hosts },
        timings = sessionTimings,
        maxEnvelopeSize = maxEnvelopeSize,
        now = now,
        relayMailboxes = relayMailboxes,
        mailboxTimings = mailboxTimings,
        relayStorageUse = VaultKeyguardRelayStorageUse(
            store = settings,
        ),
    )

    private val registryLazy = lazy {
        KeyguardSessionRegistry(
            dependencies = sessionDependencies,
            connectivityService = connectivityService,
            exchangeDeadlineMs = exchangeDeadlineMs,
        )
    }

    /** The one owner of the connections of the sync groups. */
    val registry: KeyguardSessionRegistry by registryLazy

    private val syncLazy = lazy {
        AppLinkSync(
            db = db,
            crypto = crypto,
            inbox = inbox,
            exchange = { registry },
            sessions = { sessionLauncher ?: registry },
            service = service,
            categories = categories,
            switches = switches,
            blobs = blobs,
            logRepository = logRepository,
            rosterAlerts = rosterAlerts,
            now = now,
            maxWordsPerPass = maxWordsPerPass,
        )
    }

    /** Runs the sync passes of the link. */
    val sync: AppLinkSync by syncLazy

    val enrollmentVault = KeyguardEnrollmentVault(
        db = db,
        crypto = crypto,
        deviceFactory = deviceFactory,
        authorityKeys = authorityKeys,
        hosts = hosts,
        now = now,
    )

    val enrollment = KeyguardEnrollmentCoordinator(
        roomConnector = roomConnector,
        vault = enrollmentVault,
        appLink = service,
        crypto = crypto,
        timings = enrollmentTimings,
        now = now,
    )

    /** Stops the sync passes and the sessions that started. */
    override fun close() {
        if (syncLazy.isInitialized()) sync.close()
        if (registryLazy.isInitialized()) registry.close()
    }
}
