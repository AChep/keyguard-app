package com.artemchep.keyguard.common.usecase.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import arrow.core.getOrElse
import com.artemchep.keyguard.build.FileHashes
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.CheckPasswordSetLeakRequest
import com.artemchep.keyguard.common.model.DNotification
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationId
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlert
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.ignores
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CipherSnapshot
import com.artemchep.keyguard.common.usecase.CipherSnapshotKey
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherSshKeyWeakCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetBreachesLatestDate
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetCipherSnapshots
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadAlerts
import com.artemchep.keyguard.common.usecase.ShowNotification
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.WatchtowerSyncer
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.parseHttpUrlHostOrNull
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.util.withLogTimeOfFirstEvent
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import org.kodein.di.DirectDI
import com.artemchep.keyguard.platform.leAllInstances
import org.kodein.di.direct
import org.kodein.di.instance

class WatchtowerSyncerImpl(
    private val getVaultSession: GetVaultSession,
) : WatchtowerSyncer {
    private class Holder(
        val client: WatchtowerClient,
        val notifications: WatchtowerNotifications,
    )

    constructor(directDI: DirectDI) : this(
        getVaultSession = directDI.instance(),
    )

    override fun start(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ) {
        flow
            .onState {
                getVaultSession()
                    .map { session ->
                        val key = session as? MasterSession.Key
                        key?.di?.direct?.let { direct ->
                            Holder(
                                client = WatchtowerClient(direct),
                                notifications = WatchtowerNotifications(direct),
                            )
                        }
                    }
                    .collectLatest { holder ->
                        if (holder != null) {
                            coroutineScope {
                                holder.client.launch(this)
                                holder.notifications.launch(this)
                            }
                        }
                    }
            }
            .launchIn(scope)
    }
}

private class WatchtowerNotifications(
    private val context: LeContext,
    private val getWatchtowerUnreadAlerts: GetWatchtowerUnreadAlerts,
    private val getCiphers: GetCiphers,
    private val getProfiles: GetProfiles,
    private val showNotification: ShowNotification,
    private val cryptoGenerator: CryptoGenerator,
) {
    companion object {
        private const val FLOW_DEBOUNCE_MS = 1000L
    }

    constructor(directDI: DirectDI) : this(
        context = directDI.instance(),
        getWatchtowerUnreadAlerts = directDI.instance(),
        getCiphers = directDI.instance(),
        getProfiles = directDI.instance(),
        showNotification = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

    fun launch(scope: CoroutineScope) = scope.launch {
        // A set of profiles that are not
        // hidden and should account for the
        // watchtower alerts.
        val visibleAccountIdsFlow = getProfiles()
            .map { profiles ->
                profiles
                    .mapNotNull { it.takeIf { !it.hidden }?.accountId }
                    .toSet()
            }
            .distinctUntilChanged()
        val visibleCipherIdsFlow = getCiphers()
            .map { ciphers ->
                ciphers
                    .mapNotNull { it.takeIf { !it.deleted }?.id }
                    .toSet()
            }
            .distinctUntilChanged()
        val unreadAlertsFlow = getWatchtowerUnreadAlerts()
            // Hide the non-public (hidden) accounts
            // from the notifications.
            .combine(visibleAccountIdsFlow) { alerts, publicAccountIds ->
                alerts
                    .filter { it.accountId.id in publicAccountIds }
            }
            // Hide the deleted ciphers
            // from the notifications.
            .combine(visibleCipherIdsFlow) { alerts, publicCipherIds ->
                alerts
                    .filter { it.cipherId.id in publicCipherIds }
            }
            .debounce(FLOW_DEBOUNCE_MS)

        unreadAlertsFlow
            // This also drops the first event, which
            // is exactly what we want.
            .runningReduce { oldValue, newValue ->
                val alerts = newValue
                    .filter { alert ->
                        oldValue.none { it.alertId == alert.alertId }
                    }
                if (alerts.isNotEmpty()) {
                    GlobalScope.launchNewAlertsNotification(alerts)
                }

                newValue
            }
            .launchIn(this)
    }

    private fun CoroutineScope.launchNewAlertsNotification(
        alerts: List<DWatchtowerAlert>,
    ) = launch {
        val notification = kotlin.run {
            val title = textResource(
                Res.string.watchtower_notification_new_alerts_title,
                context,
            )
            val text = alerts
                .groupBy { it.type }
                .map { alertGroup ->
                    val typeTitle = textResource(alertGroup.key.title, context)
                    "$typeTitle (+${alertGroup.value.size})"
                }
                .joinToString()
            val tag = cryptoGenerator.uuid()
            DNotification(
                id = DNotificationId.WATCHTOWER,
                tag = tag,
                title = title,
                text = text,
                channel = DNotificationChannel.WATCHTOWER,
                number = alerts.size,
            )
        }
        showNotification(notification)
            .attempt()
            .bind()
    }
}

private class WatchtowerClient(
    private val getBreaches: GetBreaches,
    private val getCipherSnapshots: GetCipherSnapshots,
    private val databaseManager: VaultDatabaseManager,
    private val logRepository: LogRepository,
    private val syncSupervisor: SupervisorRead,
    private val list: List<WatchtowerClientTyped>,
    private val defaultDispatcher: CoroutineDispatcher,
    private val dbDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "WatchtowerAlertClient"
    }

    constructor(directDI: DirectDI) : this(
        getBreaches = directDI.instance(),
        getCipherSnapshots = directDI.instance(),
        databaseManager = directDI.instance(),
        logRepository = directDI.instance(),
        syncSupervisor = directDI.instance(),
        list = directDI.leAllInstances(),
        defaultDispatcher = Dispatchers.Default.limitedParallelism(1),
        dbDispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    fun launch(scope: CoroutineScope) = scope.launch {
        val processingAllowedFlow = syncSupervisor
            .get(AccountTask.SYNC)
            .watchtowerProcessingAllowedFlow()
            .shareIn(this, SharingStarted.Eagerly, replay = 1)

        val db = databaseManager.get()
            .bind()
        val cipherSnapshotsFlow = getCipherSnapshots()
            .map(::CipherSnapshotIndex)
            .shareIn(this, SharingStarted.Eagerly, replay = 1)
        list.forEach { processor ->
            val type = processor.type
            // For how long we want to wait
            // between the requests
            val debounceTimeoutMs = when (processor.mode) {
                WatchtowerClientMode.CHANGED_ONLY -> 1000L
                WatchtowerClientMode.ALL -> 3000L
            }

            val versionFlow = processor.version()
            val requestsFlow = versionFlow
                .distinctUntilChanged()
                .flatMapLatest { version ->
                    val ciphersFlow = when (processor.mode) {
                        WatchtowerClientMode.CHANGED_ONLY -> {
                            // Only emit the changed ciphers, this
                            // helps a LOT because we avoid processing
                            // of non-changed ciphers.
                            getPendingCiphersFlow(
                                db = db,
                                type = type,
                                version = version,
                                cipherSnapshotsFlow = cipherSnapshotsFlow,
                            )
                        }

                        WatchtowerClientMode.ALL -> {
                            getAllCiphersFlow(
                                db = db,
                                type = type,
                                version = version,
                                cipherSnapshotsFlow = cipherSnapshotsFlow,
                            )
                        }
                    }
                    ciphersFlow
                        .map { ciphers ->
                            WatchtowerProcessingRequest(
                                version = version,
                                ciphers = ciphers,
                            )
                        }
                }
            requestsFlow
                .mapLatestWhenAllowed(
                    allowedFlow = processingAllowedFlow,
                    debounceMs = debounceTimeoutMs,
                ) { request ->
                    if (request.ciphers.isEmpty()) {
                        return@mapLatestWhenAllowed
                    }

                    val version = request.version
                    val ciphers = request.ciphers
                        .map(CipherSnapshot::cipher)
                    val message = "Processing watchtower alert [$type/$version]: ${ciphers.size} items"
                    logRepository.add(TAG, message)

                    val now = Clock.System.now()
                    val results = try {
                        processor.process(ciphers)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // If there's a bug in the watchtower processor then we
                        // just want to report that and not crash the app. The
                        // watchtower crashes might be quite annoying because they
                        // soft-lock you out of the app.
                        recordException(e)
                        return@mapLatestWhenAllowed
                    }
                    if (!processingAllowedFlow.first()) {
                        return@mapLatestWhenAllowed
                    }
                    currentCoroutineContext().ensureActive()

                    db.transaction {
                        if (processor.mode == WatchtowerClientMode.ALL) {
                            val requestKeys = request.ciphers
                                .map(CipherSnapshot::key)
                                .sortedBy(CipherSnapshotKey::cipherId)
                            val currentKeys = db.cipherQueries
                                .getCipherSnapshotKeys()
                                .executeAsList()
                                .map { row ->
                                    CipherSnapshotKey(
                                        cipherId = row.cipherId,
                                        dataRevCounter = row.dataRevCounter,
                                    )
                                }
                            if (requestKeys != currentKeys) {
                                return@transaction
                            }
                        }

                        val snapshotByCipherId = request.ciphers
                            .associateBy { snapshot -> snapshot.key.cipherId }
                        results.forEach { r ->
                            val snapshot = snapshotByCipherId[r.cipher.id]
                                ?: return@forEach
                            // We might be inserting a threat report on a cipher that
                            // does not exist anymore. This is fine, just ignore it.
                            runCatching {
                                db.watchtowerThreatQueries.upsert(
                                    value = r.value,
                                    threat = r.threat && !r.cipher.deleted,
                                    cipherId = r.cipher.id,
                                    type = type,
                                    reportedAt = now,
                                    version = version,
                                    cipherDataRevCounter = snapshot.key.dataRevCounter,
                                )
                            }
                        }
                    }
                }
                .flowOn(defaultDispatcher)
                .launchIn(this)
        }

        // Auto-refresh the list of breaches, it's required
        // for the watchtower to know when to re-compute some
        // of the clients.
        getBreaches(true) // force refresh
            .attempt()
            .launchIn(this)
    }

    private fun getAllCiphersFlow(
        db: Database,
        type: Long,
        version: String,
        cipherSnapshotsFlow: Flow<CipherSnapshotIndex>,
    ): Flow<List<CipherSnapshot>> = db.watchtowerThreatQueries
        .hasPendingCiphers(
            type = type,
            version = version,
        )
        .asFlow()
        .mapToOne(dbDispatcher)
        .distinctUntilChanged()
        .combine(cipherSnapshotsFlow) { hasPending, cipherSnapshots ->
            if (hasPending) {
                cipherSnapshots.snapshots
            } else {
                emptyList()
            }
        }
        .distinctUntilChanged()

    private fun getPendingCiphersFlow(
        db: Database,
        type: Long,
        version: String,
        cipherSnapshotsFlow: Flow<CipherSnapshotIndex>,
    ): Flow<List<CipherSnapshot>> = db.watchtowerThreatQueries
        .getPendingCipherKeys(
            type = type,
            version = version,
            limit = 500L,
        )
        .asFlow()
        .mapToList(dbDispatcher)
        .map { rows ->
            rows.map { row ->
                WatchtowerPendingCipherKey(
                    cipherId = row.cipherId,
                    dataRevCounter = row.dataRevCounter,
                )
            }
        }
        .distinctUntilChanged()
        .combine(cipherSnapshotsFlow, ::resolvePendingCipherSnapshots)
        .distinctUntilChanged()
}

private data class WatchtowerProcessingRequest(
    val version: String,
    val ciphers: List<CipherSnapshot>,
)

internal class CipherSnapshotIndex(
    val snapshots: List<CipherSnapshot>,
) {
    val byCipherId = snapshots
        .associateBy { snapshot -> snapshot.key.cipherId }
}

internal data class WatchtowerPendingCipherKey(
    val cipherId: String,
    val dataRevCounter: Long,
)

internal fun resolvePendingCipherSnapshots(
    keys: List<WatchtowerPendingCipherKey>,
    index: CipherSnapshotIndex,
): List<CipherSnapshot> = keys.mapNotNull { key ->
    index.byCipherId[key.cipherId]
        ?.takeIf { snapshot -> snapshot.key.dataRevCounter == key.dataRevCounter }
}

private const val WATCHTOWER_PROCESSING_RESUME_DELAY_MS = 2_000L

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Set<*>>.watchtowerProcessingAllowedFlow(
    resumeDelayMs: Long = WATCHTOWER_PROCESSING_RESUME_DELAY_MS,
): Flow<Boolean> = map { accounts -> accounts.isNotEmpty() }
    .distinctUntilChanged()
    .runningFold(null as WatchtowerProcessingGateState?) { state, syncing ->
        WatchtowerProcessingGateState(
            syncing = syncing,
            seenBefore = state != null,
        )
    }
    .mapNotNull { it }
    .flatMapLatest { state ->
        flow {
            if (state.syncing) {
                emit(false)
            } else {
                if (state.seenBefore) {
                    delay(resumeDelayMs)
                }
                emit(true)
            }
        }
    }
    .distinctUntilChanged()

private data class WatchtowerProcessingGateState(
    val syncing: Boolean,
    val seenBefore: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.mapLatestWhenAllowed(
    allowedFlow: Flow<Boolean>,
    debounceMs: Long,
    transform: suspend (T) -> Unit,
): Flow<Unit> = combine(
    allowedFlow.distinctUntilChanged(),
) { value, allowed ->
    value to allowed
}
    .mapLatest { (value, allowed) ->
        if (!allowed) {
            return@mapLatest
        }

        delay(debounceMs)
        transform(value)
    }

data class WatchtowerClientResult(
    val value: String? = null,
    val threat: Boolean,
    val cipher: DSecret,
)

enum class WatchtowerClientMode {
    CHANGED_ONLY,
    ALL,
}

interface WatchtowerClientTyped {
    val type: Long

    val mode: WatchtowerClientMode get() = WatchtowerClientMode.CHANGED_ONLY

    fun version(): Flow<String>

    suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult>
}

class WatchtowerPasswordStrength(
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.WEAK_PASSWORD.value

    constructor(directDI: DirectDI) : this(
    )

    override fun version() = flowOf("1")

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val value = cipher.login?.password
                val threat = cipher.login?.passwordStrength?.score == PasswordStrength.Score.Weak
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }
}

class WatchtowerSshKeyStrength(
    private val cipherSshKeyWeakCheck: CipherSshKeyWeakCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.WEAK_SSH_KEY.value

    constructor(directDI: DirectDI) : this(
        cipherSshKeyWeakCheck = directDI.instance(),
    )

    override fun version() = flowOf("1")

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val threat = !shouldIgnore(cipher) &&
                        cipherSshKeyWeakCheck(cipher)
                WatchtowerClientResult(
                    value = cipher.sshKey?.privateKey,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.WEAK_SSH_KEY)
}

class WatchtowerPasswordPwned(
    private val checkPasswordSetLeak: CheckPasswordSetLeak,
    private val getBreachesLatestDate: GetBreachesLatestDate,
    private val getCheckPwnedPasswords: GetCheckPwnedPasswords,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.PWNED_PASSWORD.value

    constructor(directDI: DirectDI) : this(
        checkPasswordSetLeak = directDI.instance(),
        getBreachesLatestDate = directDI.instance(),
        getCheckPwnedPasswords = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getDatabaseVersionFlow(),
        getCheckPwnedPasswords()
            .map {
                it.int.toString()
            },
        version = "2",
    )

    private fun getDatabaseVersionFlow() = getBreachesLatestDate()
        .map { it.toString() }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val set = buildPwnedPasswordsState(
            ciphers = ciphers,
        )
            .asSequence()
            .mapNotNull { entry ->
                entry.key.takeIf { entry.value > 0 }
            }
            .toSet()
        return ciphers
            .map { cipher ->
                val value = cipher.login?.password
                val threat = cipher.login
                    ?.password
                    ?.let { password ->
                        password in set &&
                                !shouldIgnore(cipher)
                    } == true
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private suspend fun buildPwnedPasswordsState(
        ciphers: List<DSecret>,
    ): Map<String, Int> = ioEffect {
        val passwords = ciphers
            .asSequence()
            .mapNotNull { cipher ->
                val shouldIgnore = shouldIgnore(cipher)
                if (shouldIgnore) {
                    return@mapNotNull null
                }

                cipher.login?.password
            }
            .toSet()
        return@ioEffect checkPasswordSetLeak(CheckPasswordSetLeakRequest(passwords))
            .map {
                it.mapValues { it.value?.occurrences ?: 0 }
            }
            .attempt()
            .bind()
            .getOrElse { emptyMap() }
    }
        .measure { duration, mutableMap ->
            println("pwned calculated in $duration")
        }
        .bind()

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.PWNED_PASSWORD)
}

class WatchtowerWebsitePwned(
    private val getAutofillDefaultMatchDetection: GetAutofillDefaultMatchDetection,
    private val cipherBreachCheck: CipherBreachCheck,
    private val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    private val getBreaches: GetBreaches,
    private val getBreachesLatestDate: GetBreachesLatestDate,
    private val getEquivalentDomains: GetEquivalentDomains,
    private val getCheckPwnedServices: GetCheckPwnedServices,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.PWNED_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        getAutofillDefaultMatchDetection = directDI.instance(),
        cipherBreachCheck = directDI.instance(),
        equivalentDomainsBuilderFactory = directDI.instance(),
        getBreaches = directDI.instance(),
        getBreachesLatestDate = directDI.instance(),
        getEquivalentDomains = directDI.instance(),
        getCheckPwnedServices = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getDatabaseVersionFlow(),
        getAutofillDefaultMatchDetection()
            .map { it.name },
        getEquivalentDomains()
            .map { it.size.toString() },
        getCheckPwnedServices()
            .map {
                it.int.toString()
            },
        version = "2",
    )

    private fun getDatabaseVersionFlow() = getBreachesLatestDate()
        .map { it.toString() }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val equivalentDomainsBuilder = equivalentDomainsBuilderFactory
            .build()
        val defaultMatchDetection = getAutofillDefaultMatchDetection()
            .first()
        val set = buildDuplicatesState(
            ciphers = ciphers,
            defaultMatchDetection = defaultMatchDetection,
            equivalentDomainsBuilder = equivalentDomainsBuilder,
        )
        return ciphers
            .map { cipher ->
                val value = sequence<String> {
                    val revisionDate = cipher.login?.passwordRevisionDate
                    if (revisionDate != null) {
                        yield(revisionDate.toString())
                    }

                    cipher.uris.forEach {
                        yield(it.uri)
                    }
                }.joinToString()
                val threat = cipher.id in set
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private suspend fun buildDuplicatesState(
        ciphers: List<DSecret>,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomainsBuilder: EquivalentDomainsBuilder,
    ): Set<String> = ioEffect {
        val breaches = getBreaches(false)
            .handleError {
                HibpBreachGroup(emptyList())
            }
            .bind()

        ciphers
            .filter { cipher ->
                val shouldIgnore = shouldIgnore(cipher)
                if (shouldIgnore) {
                    return@filter false
                }

                val equivalentDomains = equivalentDomainsBuilder
                    .getAndCache(cipher.accountId)
                cipherBreachCheck(cipher, breaches, defaultMatchDetection, equivalentDomains)
                    .handleError { false }
                    .bind()
            }
            .map { cipher -> cipher.id }
            .toSet()
    }
        .measure { duration, mutableMap ->
            println("pwned accounts calculated in $duration")
        }
        .bind()

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.PWNED_WEBSITE)
}

class WatchtowerInactivePasskey(
    private val tldService: TldService,
    private val passKeyService: PassKeyService,
    private val getPasskeys: GetPasskeys,
    private val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    private val getEquivalentDomains: GetEquivalentDomains,
    private val getCheckPasskeys: GetCheckPasskeys,
) : WatchtowerClientTyped {
    companion object {
        context(tldService: TldService)
        suspend fun hasAlert(
            cipher: DSecret,
            passkeyLibrary: List<PassKeyServiceInfo>,
            equivalentDomainsBuilder: EquivalentDomainsBuilder,
        ): String? = hasAlert(
            cipher = cipher,
            passkeyIndex = PasskeyServiceDomainIndex(passkeyLibrary),
            equivalentDomainsBuilder = equivalentDomainsBuilder,
        )

        context(tldService: TldService)
        private suspend fun hasAlert(
            cipher: DSecret,
            passkeyIndex: PasskeyServiceDomainIndex,
            equivalentDomainsBuilder: EquivalentDomainsBuilder,
        ): String? {
            if (!canHaveAlert(cipher)) {
                return null
            }

            val equivalentDomains = equivalentDomainsBuilder
                .getAndCache(cipher.accountId)
            return hasAlert(
                cipher = cipher,
                passkeyIndex = passkeyIndex,
                equivalentDomains = equivalentDomains,
            )
        }

        context(tldService: TldService)
        private suspend fun hasAlert(
            cipher: DSecret,
            passkeyIndex: PasskeyServiceDomainIndex,
            equivalentDomains: EquivalentDomains,
        ): String? {
            var firstDomain: String? = null
            var multipleDomains: MutableSet<String>? = null
            cipher.uris.forEach { uri ->
                val info = findMatch(
                    uri = uri,
                    passkeyIndex = passkeyIndex,
                    equivalentDomains = equivalentDomains,
                ) ?: return@forEach
                val domain = info.domain
                val existingFirstDomain = firstDomain
                if (existingFirstDomain == null) {
                    firstDomain = domain
                } else if (domain != existingFirstDomain) {
                    val domains = multipleDomains
                        ?: mutableSetOf(existingFirstDomain)
                            .also { multipleDomains = it }
                    domains += domain
                }
            }
            return multipleDomains
                ?.sorted()
                ?.joinToString()
                ?: firstDomain
        }

        context(tldService: TldService)
        suspend fun match(
            cipher: DSecret,
            passkeyLibrary: List<PassKeyServiceInfo>,
            equivalentDomains: EquivalentDomains,
        ) = match(
            cipher = cipher,
            passkeyIndex = PasskeyServiceDomainIndex(passkeyLibrary),
            equivalentDomains = equivalentDomains,
        )

        context(tldService: TldService)
        private fun match(
            cipher: DSecret,
            passkeyIndex: PasskeyServiceDomainIndex,
            equivalentDomains: EquivalentDomains,
        ) = cipher.uris
            .asFlow()
            .mapNotNull { uri ->
                findMatch(
                    uri = uri,
                    passkeyIndex = passkeyIndex,
                    equivalentDomains = equivalentDomains,
                )
            }

        context(tldService: TldService)
        private suspend fun findMatch(
            uri: DSecret.Uri,
            passkeyIndex: PasskeyServiceDomainIndex,
            equivalentDomains: EquivalentDomains,
        ): PassKeyServiceInfo? {
            val host = parseHost(uri)
                ?: return null
            return findMatch(
                host = host,
                passkeyIndex = passkeyIndex,
                equivalentDomains = equivalentDomains,
            )
        }

        context(tldService: TldService)
        private suspend fun findMatch(
            host: String,
            passkeyIndex: PasskeyServiceDomainIndex,
            equivalentDomains: EquivalentDomains,
        ): PassKeyServiceInfo? {
            val directMatch = passkeyIndex.findFirstMatchOrNull(host = host)
            if (directMatch != null) {
                return directMatch.takeIf { "signin" in it.features }
            }

            val domain = tldService.getDomainName(host)
                .bind()
            val domainEq = equivalentDomains.domains[domain.lowercase()]
                ?: return null
            return passkeyIndex
                .findFirstEquivalentMatchOrNull(
                    host = host,
                    domain = domain,
                    equivalentDomains = domainEq,
                )
                ?.takeIf { "signin" in it.features }
        }

        private fun PasskeyServiceDomainIndex.findFirstEquivalentMatchOrNull(
            host: String,
            domain: String,
            equivalentDomains: List<String>,
        ): PassKeyServiceInfo? {
            val prefix = host.removeSuffix(domain)
            return equivalentDomains
                .firstNotNullOfOrNull { d ->
                    val h = prefix + d
                    findFirstMatchOrNull(host = h)
                }
        }

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlertType.PASSKEY_WEBSITE)

        private fun canHaveAlert(
            cipher: DSecret,
        ) = !shouldIgnore(cipher) &&
            cipher.login?.fido2Credentials.isNullOrEmpty() &&
            cipher.uris.isNotEmpty()
    }

    override val type: Long
        get() = DWatchtowerAlertType.PASSKEY_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        tldService = directDI.instance(),
        passKeyService = directDI.instance(),
        getPasskeys = directDI.instance(),
        equivalentDomainsBuilderFactory = directDI.instance(),
        getEquivalentDomains = directDI.instance(),
        getCheckPasskeys = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        flowOf(passKeyService.version),
        flowOf(tldService.version),
        getEquivalentDomains()
            .map { it.size.toString() },
        getCheckPasskeys()
            .map {
                it.int.toString()
            },
        version = "2",
    )

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val equivalentDomainsBuilder = equivalentDomainsBuilderFactory
            .build()
        val passkeyLibrary = getPasskeys()
            .crashlyticsTap()
            .attempt()
            .bind()
            .getOrNull()
            .orEmpty()
        val passkeyIndex = PasskeyServiceDomainIndex(passkeyLibrary)
        val equivalentDomainsByAccount = mutableMapOf<String, EquivalentDomains>()

        return ciphers
            .map { cipher ->
                val value = if (canHaveAlert(cipher)) {
                    val equivalentDomains = equivalentDomainsByAccount[cipher.accountId]
                        ?: equivalentDomainsBuilder
                            .getAndCache(cipher.accountId)
                            .also { equivalentDomainsByAccount[cipher.accountId] = it }
                    with(tldService) {
                        hasAlert(
                            cipher = cipher,
                            passkeyIndex = passkeyIndex,
                            equivalentDomains = equivalentDomains,
                        )
                    }
                } else {
                    null
                }
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }
}

internal class PasskeyServiceDomainIndex(
    passkeyLibrary: List<PassKeyServiceInfo>,
) {
    private data class IndexedService(
        val index: Int,
        val service: PassKeyServiceInfo,
    )

    private val servicesByDomain: Map<String, IndexedService> = buildMap {
        passkeyLibrary.forEachIndexed { index, service ->
            service.domains.forEach { domain ->
                if (domain !in this) {
                    put(
                        domain,
                        IndexedService(
                            index = index,
                            service = service,
                        ),
                    )
                }
            }
        }
    }

    fun findFirstMatchOrNull(
        host: String,
    ): PassKeyServiceInfo? {
        var match = servicesByDomain[host]
        var separatorIndex = host.indexOf('.')
        while (separatorIndex >= 0 && separatorIndex < host.lastIndex) {
            val domain = host.substring(separatorIndex + 1)
            val candidate = servicesByDomain[domain]
            if (candidate != null && (match == null || candidate.index < match.index)) {
                match = candidate
            }
            separatorIndex = host.indexOf('.', separatorIndex + 1)
        }
        return match?.service
    }
}

internal class TwoFaServiceDomainIndex(
    tfaLibrary: List<TwoFaServiceInfo>,
) {
    private val servicesByDomain: Map<String, TwoFaServiceInfo> = buildMap {
        tfaLibrary.forEach { service ->
            service.domains.forEach { domain ->
                if (domain !in this) {
                    put(domain, service)
                }
            }
        }
    }

    fun findFirstMatchOrNull(
        host: String,
    ): TwoFaServiceInfo? = servicesByDomain[host]
}

class WatchtowerIncomplete(
    private val cipherIncompleteCheck: CipherIncompleteCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.INCOMPLETE.value

    constructor(directDI: DirectDI) : this(
        cipherIncompleteCheck = directDI.instance(),
    )

    override fun version() = flowOf("1")

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        val isIncomplete = cipherIncompleteCheck(cipher)
        if (!isIncomplete) {
            return null
        }

        val group = listOfNotNull(
            cipher.login?.username,
            cipher.login?.password,
        ).joinToString(separator = "|")
        return group.takeIf { it.isNotEmpty() }
            ?: "empty"
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.INCOMPLETE)
}

class WatchtowerExpiring(
    private val cipherExpiringCheck: CipherExpiringCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.EXPIRING.value

    constructor(directDI: DirectDI) : this(
        cipherExpiringCheck = directDI.instance(),
    )

    override fun version() = flow {
        // Refresh daily
        val seconds = Clock.System.now().epochSeconds
        val days = seconds / 86400L
        emit(days.toString())
    }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val now = Clock.System.now()
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                    now = now,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
        now: Instant,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        val isExpiring = cipherExpiringCheck(cipher, now) != null
        if (!isExpiring) {
            return null
        }

        val group = listOfNotNull(
            cipher.login?.username,
            cipher.login?.password,
        ).joinToString(separator = "|")
        return group.takeIf { it.isNotEmpty() }
            ?: "empty"
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.EXPIRING)
}

class WatchtowerUnsecureWebsite(
    private val cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.UNSECURE_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        cipherUnsecureUrlCheck = directDI.instance(),
    )

    override fun version() = flowOf(FileHashes.public_suffix_list)

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        var firstUri: String? = null
        var multipleUris: StringBuilder? = null
        cipher.uris.forEach { uri ->
            if (!cipherUnsecureUrlCheck(uri.uri)) {
                return@forEach
            }

            val uriValue = uri.toString()
            val existingFirstUri = firstUri
            if (existingFirstUri == null) {
                firstUri = uriValue
            } else {
                val builder = multipleUris
                    ?: StringBuilder(existingFirstUri)
                        .also { multipleUris = it }
                builder.append(", ")
                builder.append(uriValue)
            }
        }
        return multipleUris?.toString() ?: firstUri
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.UNSECURE_WEBSITE)
}

class WatchtowerInactiveTfa(
    private val tldService: TldService,
    private val tfaService: TwoFaService,
    private val getTwoFa: GetTwoFa,
    private val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    private val getEquivalentDomains: GetEquivalentDomains,
    private val getCheckTwoFA: GetCheckTwoFA,
) : WatchtowerClientTyped {
    companion object {
        context(tldService: TldService)
        suspend fun hasAlert(
            cipher: DSecret,
            tfaLibrary: List<TwoFaServiceInfo>,
            equivalentDomainsBuilder: EquivalentDomainsBuilder,
        ): String? = hasAlert(
            cipher = cipher,
            tfaIndex = TwoFaServiceDomainIndex(tfaLibrary),
            equivalentDomainsBuilder = equivalentDomainsBuilder,
        )

        context(tldService: TldService)
        private suspend fun hasAlert(
            cipher: DSecret,
            tfaIndex: TwoFaServiceDomainIndex,
            equivalentDomainsBuilder: EquivalentDomainsBuilder,
        ): String? {
            if (!canHaveAlert(cipher)) {
                return null
            }

            val equivalentDomains = equivalentDomainsBuilder
                .getAndCache(cipher.accountId)
            return hasAlert(
                cipher = cipher,
                tfaIndex = tfaIndex,
                equivalentDomains = equivalentDomains,
            )
        }

        context(tldService: TldService)
        private suspend fun hasAlert(
            cipher: DSecret,
            tfaIndex: TwoFaServiceDomainIndex,
            equivalentDomains: EquivalentDomains,
        ): String? {
            var firstDomain: String? = null
            var multipleDomains: MutableSet<String>? = null
            cipher.uris.forEach { uri ->
                val info = findMatch(
                    uri = uri,
                    tfaIndex = tfaIndex,
                    equivalentDomains = equivalentDomains,
                ) ?: return@forEach
                val domain = info.domain
                val existingFirstDomain = firstDomain
                if (existingFirstDomain == null) {
                    firstDomain = domain
                } else if (domain != existingFirstDomain) {
                    val domains = multipleDomains
                        ?: mutableSetOf(existingFirstDomain)
                            .also { multipleDomains = it }
                    domains += domain
                }
            }
            return multipleDomains
                ?.sorted()
                ?.joinToString()
                ?: firstDomain
        }

        context(tldService: TldService)
        fun match(
            cipher: DSecret,
            tfaLibrary: List<TwoFaServiceInfo>,
            equivalentDomains: EquivalentDomains,
        ) = match(
            cipher = cipher,
            tfaIndex = TwoFaServiceDomainIndex(tfaLibrary),
            equivalentDomains = equivalentDomains,
        )

        context(tldService: TldService)
        private fun match(
            cipher: DSecret,
            tfaIndex: TwoFaServiceDomainIndex,
            equivalentDomains: EquivalentDomains,
        ) = cipher.uris
            .asFlow()
            .mapNotNull { uri ->
                findMatch(
                    uri = uri,
                    tfaIndex = tfaIndex,
                    equivalentDomains = equivalentDomains,
                )
            }

        context(tldService: TldService)
        private suspend fun findMatch(
            uri: DSecret.Uri,
            tfaIndex: TwoFaServiceDomainIndex,
            equivalentDomains: EquivalentDomains,
        ): TwoFaServiceInfo? {
            val host = parseHost(uri)
                ?: return null
            val directMatch = tfaIndex.findFirstMatchOrNull(host = host)
            if (directMatch != null) {
                return directMatch.takeIf { "totp" in it.tfa }
            }

            val domain = tldService.getDomainName(host)
                .bind()
            val domainEq = equivalentDomains.domains[domain.lowercase()]
                ?: return null
            return tfaIndex
                .findFirstEquivalentMatchOrNull(
                    host = host,
                    domain = domain,
                    equivalentDomains = domainEq,
                )
                ?.takeIf { "totp" in it.tfa }
        }

        private fun TwoFaServiceDomainIndex.findFirstEquivalentMatchOrNull(
            host: String,
            domain: String,
            equivalentDomains: List<String>,
        ): TwoFaServiceInfo? {
            val prefix = host.removeSuffix(domain)
            return equivalentDomains
                .firstNotNullOfOrNull { d ->
                    val h = prefix + d
                    findFirstMatchOrNull(host = h)
                }
        }

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlertType.TWO_FA_WEBSITE)

        private fun canHaveAlert(
            cipher: DSecret,
        ) = !shouldIgnore(cipher) &&
            cipher.uris.isNotEmpty() &&
            cipher.login?.totp == null &&
            (
                cipher.login?.fido2Credentials.isNullOrEmpty() ||
                    !cipher.login?.password.isNullOrEmpty()
                )
    }

    override val type: Long
        get() = DWatchtowerAlertType.TWO_FA_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        tldService = directDI.instance(),
        tfaService = directDI.instance(),
        getTwoFa = directDI.instance(),
        equivalentDomainsBuilderFactory = directDI.instance(),
        getEquivalentDomains = directDI.instance(),
        getCheckTwoFA = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        flowOf(tfaService.version),
        flowOf(tldService.version),
        getEquivalentDomains()
            .map { it.size.toString() },
        getCheckTwoFA()
            .map {
                it.int.toString()
            },
        version = "2",
    )

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val equivalentDomainsBuilder = equivalentDomainsBuilderFactory
            .build()
        val tfaLibrary = getTwoFa()
            .crashlyticsTap()
            .attempt()
            .bind()
            .getOrNull()
            .orEmpty()
        val tfaIndex = TwoFaServiceDomainIndex(tfaLibrary)
        val equivalentDomainsByAccount = mutableMapOf<String, EquivalentDomains>()

        return ciphers
            .map { cipher ->
                val value = if (canHaveAlert(cipher)) {
                    val equivalentDomains = equivalentDomainsByAccount[cipher.accountId]
                        ?: equivalentDomainsBuilder
                            .getAndCache(cipher.accountId)
                            .also { equivalentDomainsByAccount[cipher.accountId] = it }
                    with(tldService) {
                        hasAlert(
                            cipher = cipher,
                            tfaIndex = tfaIndex,
                            equivalentDomains = equivalentDomains,
                        )
                    }
                } else {
                    null
                }
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }
}

class WatchtowerDuplicateUris(
    private val getAutofillDefaultMatchDetection: GetAutofillDefaultMatchDetection,
    private val cipherUrlDuplicateCheck: CipherUrlDuplicateCheck,
    private val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.DUPLICATE_URIS.value

    constructor(directDI: DirectDI) : this(
        getAutofillDefaultMatchDetection = directDI.instance(),
        cipherUrlDuplicateCheck = directDI.instance(),
        equivalentDomainsBuilderFactory = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getAutofillDefaultMatchDetection()
            .map { it.name },
        version = "2",
    )

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val equivalentDomainsBuilder = equivalentDomainsBuilderFactory.build()
        val defaultMatchDetection = getAutofillDefaultMatchDetection()
            .first()
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                    defaultMatchDetection = defaultMatchDetection,
                    equivalentDomainsBuilder = equivalentDomainsBuilder,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private suspend fun hasAlert(
        cipher: DSecret,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomainsBuilder: EquivalentDomainsBuilder,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        return match(cipher, defaultMatchDetection, equivalentDomainsBuilder)
    }

    private suspend fun match(
        cipher: DSecret,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomainsBuilder: EquivalentDomainsBuilder,
    ) = kotlin.run {
        val uris = cipher.uris
        if (uris.isEmpty()) {
            return null
        }

        val equivalentDomains = equivalentDomainsBuilder
            .getAndCache(cipher.accountId)
        val out = mutableSetOf<String>()
        for (i in 0..<uris.lastIndex) {
            for (j in i + 1..uris.lastIndex) {
                val a = uris[i]
                val b = uris[j]
                val forwardDuplicate = try {
                    cipherUrlDuplicateCheck(
                        a,
                        b,
                        defaultMatchDetection,
                        equivalentDomains,
                    ).bind() != null
                } catch (e: Throwable) {
                    e.throwIfFatalOrCancellation()
                    false
                }
                val duplicate = forwardDuplicate || try {
                    cipherUrlDuplicateCheck(
                        b,
                        a,
                        defaultMatchDetection,
                        equivalentDomains,
                    ).bind() != null
                } catch (e: Throwable) {
                    e.throwIfFatalOrCancellation()
                    false
                }
                if (duplicate) {
                    out += a.uri
                    out += b.uri
                }
            }
        }

        out
            .takeIf { it.isNotEmpty() }
            ?.joinToString()
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.DUPLICATE_URIS)
}

class WatchtowerBroadUris(
    private val getAutofillDefaultMatchDetection: GetAutofillDefaultMatchDetection,
    private val cipherUrlBroadCheck: CipherUrlBroadCheck,
    private val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.BROAD_URIS.value

    override val mode: WatchtowerClientMode
        get() = WatchtowerClientMode.ALL

    constructor(directDI: DirectDI) : this(
        getAutofillDefaultMatchDetection = directDI.instance(),
        cipherUrlBroadCheck = directDI.instance(),
        equivalentDomainsBuilderFactory = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getAutofillDefaultMatchDetection()
            .map { it.name },
        version = "1",
    )

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val equivalentDomainsBuilder = equivalentDomainsBuilderFactory.build()
        val defaultMatchDetection = getAutofillDefaultMatchDetection()
            .first()

        val allActiveCiphers = ciphers
            .filter { !it.deleted && !shouldIgnore(it) }
        val result = cipherUrlBroadCheck(
            allActiveCiphers,
            defaultMatchDetection,
            equivalentDomainsBuilder,
        ).bind()
        val resultMap = result.associateBy { it.cipher.id }

        return ciphers
            .map { cipher ->
                val data = resultMap[cipher.id]
                val threat = data != null
                WatchtowerClientResult(
                    value = data?.value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.BROAD_URIS)
}

private fun parseHost(uri: DSecret.Uri) = parseHttpUrlHostOrNull(
    url = uri.uri,
    removeWww = true,
)

private fun combineJoinToVersion(
    vararg flows: Flow<String>,
    version: String? = null,
): Flow<String> = combine(
    flows = flows,
) {
    val data = it.joinToString(separator = "|")
    if (version != null) {
        "$version|$data"
    } else data
}
