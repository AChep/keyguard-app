package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.biFlatTap
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.SyncProgress
import com.artemchep.keyguard.common.model.SyncScope
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenEquivalentDomain
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.hasPendingAttachmentMutations
import com.artemchep.keyguard.core.store.bitwarden.hasPendingFileUpload
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.api.SyncEngine
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.revisionDate
import com.artemchep.keyguard.provider.bitwarden.api.builder.sync
import com.artemchep.keyguard.provider.bitwarden.api.merge
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.appendOrganizationToken
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken
import com.artemchep.keyguard.provider.bitwarden.crypto.appendUserToken
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.ProfileEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SyncEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.requireCleanForRevisionCache
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.CipherSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.CollectionSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.FolderSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.OrganizationSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.SendSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncConfig
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.SyncCoordinator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.CipherSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.CollectionSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.FolderSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.OrganizationSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.SendSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByBitwardenToken
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * Entity types that can be selectively included in a sync run.
 *
 * Pass a subset to [SyncByBitwardenTokenV2Impl.syncV2] via
 * [entityTypesToSync] to sync only those types. When [CIPHERS]
 * is requested, [FOLDERS] is auto-included because cipher sync
 * depends on folder ID mappings. Profile and equivalent domains
 * are always synced regardless of this filter.
 */
enum class SyncEntityType {
    FOLDERS,
    CIPHERS,
    COLLECTIONS,
    ORGANIZATIONS,
    SENDS,
}

/**
 * V2 implementation of Bitwarden account sync.
 *
 * Orchestrates the full sync pipeline:
 * 1. Fetch server revision date (lightweight pre-check).
 * 2. Skip if revision unchanged and no retryable local errors.
 * 3. Fetch full `GET /sync` response.
 * 4. Empty vault safety check.
 * 5. Build crypto context (user + profile + org keys).
 * 6. Sync profile (inline, security stamp verification).
 * 7. Sync equivalent domains (inline, full replace).
 * 8. Sync folders → build folder ID mappings.
 * 9. Sync ciphers (with bulk server ops).
 * 10. Sync collections, organizations, sends.
 * 11. Aggregate results, store revision date.
 *
 * Each entity type sync is error-isolated: one type's failure
 * does not abort the others. Cancellation is checked between
 * every entity type block via [ensureActive].
 *
 * The [invoke] method wraps the sync in [Watchdog.track] and
 * records success/failure metadata in [BitwardenMeta].
 */
class SyncByBitwardenTokenV2Impl(
    private val logRepository: LogRepository,
    private val cipherEncryptor: CipherEncryptor,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val getPasswordStrength: GetPasswordStrength,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: VaultDatabaseManager,
    private val dbSyncer: DatabaseSyncer,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val watchdog: Watchdog,
) : SyncByBitwardenToken {
    companion object {
        private const val TAG = "SyncByIdV2.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        cipherEncryptor = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        getPasswordStrength = directDI.instance(),
        json = directDI.instance(),
        httpClient = directDI.instance(),
        db = directDI.instance(),
        dbSyncer = directDI.instance(),
        pendingUploadCoordinator = directDI.instance(),
        watchdog = directDI.instance(),
    )

    override fun invoke(user: BitwardenToken): IO<Unit> =
        watchdog
            .track(
                accountId = AccountId(user.id),
                accountTask = AccountTask.SYNC,
            ) {
                val scope =
                    object : SyncScope {
                        override suspend fun post(
                            title: String,
                            progress: SyncProgress.Progress?,
                        ) {
                            logRepository.add(TAG, title)
                        }
                    }
                withRefreshableAccessToken(
                    base64Service = base64Service,
                    httpClient = httpClient,
                    json = json,
                    db = db,
                    user = user,
                ) { latestUser ->
                    with(scope) {
                        syncV2(latestUser)
                    }
                }
            }.biFlatTap(
                ifException = { e ->
                    db.mutate(TAG) {
                        val dao = it.metaQueries
                        val existingMeta = dao
                            .getByAccountId(accountId = user.id)
                            .executeAsList()
                            .firstOrNull()
                            ?.data_

                        val reason = e.localizedMessage ?: e.message
                        val requiresAuthentication = requiresAuthenticationForSyncFailure(e)

                        val now = Clock.System.now()
                        val meta =
                            BitwardenMeta(
                                accountId = user.id,
                                lastSyncTimestamp = existingMeta?.lastSyncTimestamp,
                                lastSyncResult =
                                    BitwardenMeta.LastSyncResult.Failure(
                                        timestamp = now,
                                        reason = reason,
                                        requiresAuthentication = requiresAuthentication,
                                    ),
                                lastServerRevisionDate = existingMeta?.lastServerRevisionDate,
                                lastSyncServiceVersion = existingMeta?.lastSyncServiceVersion,
                            )

                        dao.insert(
                            accountId = user.id,
                            data = meta,
                        )
                    }
                },
                ifSuccess = {
                    db.mutate(TAG) {
                        val dao = it.metaQueries
                        val existingMeta =
                            dao
                                .getByAccountId(accountId = user.id)
                                .executeAsList()
                                .firstOrNull()
                                ?.data_

                        val now = Clock.System.now()
                        val meta =
                            BitwardenMeta(
                                accountId = user.id,
                                lastSyncTimestamp = now,
                                lastSyncResult = BitwardenMeta.LastSyncResult.Success,
                                lastServerRevisionDate = existingMeta?.lastServerRevisionDate,
                                lastSyncServiceVersion = existingMeta?.lastSyncServiceVersion,
                            )

                        dao.insert(
                            accountId = user.id,
                            data = meta,
                        )
                    }
                },
            ).measure { duration, _ ->
                val message = "Synced Bitwarden account in $duration."
                logRepository.post(TAG, message)
            }

    // ---------------------------------------------------------------
    // V2 sync orchestration
    // ---------------------------------------------------------------

    /**
     * Core sync logic. Fetches server state, diffs against local,
     * and executes the resulting plan for each entity type.
     *
     * @param entityTypesToSync when non-null, only the specified
     *   entity types are synced (profile and equivalent domains
     *   are always included). When `null`, all types are synced.
     */
    context(SyncScope)
    private suspend fun syncV2(
        user: BitwardenToken,
        entityTypesToSync: Set<SyncEntityType>? = null,
    ) {
        val env = user.env.back()
        val api = env.api
        val token = requireNotNull(user.token).accessToken
        val database = db.get().bind()

        val serverRevisionDate =
            fetchServerRevisionDateOrNull {
                api.accounts.revisionDate(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                )
            }

        if (entityTypesToSync == null &&
            shouldSkipFullSync(user.id, database, serverRevisionDate)
        ) {
            post(title = "Server revision date unchanged, skipping full sync.")
            return
        }

        post(title = "Send sync request.")
        val response =
            api.sync(
                httpClient = httpClient,
                env = env,
                token = token,
            )

        checkForEmptyVault(database, user, env, response)

        requireTrustedProfile(
            database = database,
            user = user,
            response = response,
        )
        val crypto = buildCrypto(user = user, profile = response.profile)

        // --- Profile (inline, always synced) ---
        post(title = "Syncing a profile entity.")
        val newProfile =
            syncProfile(
                database = database,
                user = user,
                response = response,
                crypto = crypto,
            )
        coroutineContext.ensureActive()

        // --- Equivalent Domains (inline, always synced) ---
        post(title = "Syncing equivalent domains entities.")
        syncEquivalentDomains(
            database = database,
            user = user,
            response = response,
            crypto = crypto,
        )
        coroutineContext.ensureActive()

        val coordinator = SyncCoordinator()
        val outcomes = mutableMapOf<String, EntityTypeOutcome>()

        fun shouldSync(type: SyncEntityType): Boolean =
            entityTypesToSync == null || type in entityTypesToSync

        // Folders are auto-included when CIPHERS is requested
        // because cipher sync depends on folder ID mappings.
        val shouldSyncFolders =
            shouldSync(SyncEntityType.FOLDERS) ||
                shouldSync(SyncEntityType.CIPHERS)

        // --- Folders ---
        val folderResult: EntityTypeOutcome?
        if (shouldSyncFolders) {
            post(title = "Syncing folder entities.")
            val existingFolders =
                database.folderQueries
                    .getByAccountId(accountId = user.id)
                    .executeAsList()
                    .map { it.data_ }
                    .filterByAccountId(user.id) { it.accountId }

            val folderOps =
                FolderSyncOps(
                    accountId = user.id,
                    db = database,
                    crypto = crypto,
                    cryptoGenerator = cryptoGenerator,
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    foldersApi = api.folders,
                )
            folderResult =
                coordinator.safeSyncEntityType(
                    EntitySyncConfig(
                        name = "folders",
                        strategy = FolderSyncStrategy(),
                        localEntities = existingFolders,
                        serverEntities = response.folders.orEmpty(),
                        ops = folderOps,
                    ),
                )
            outcomes["folders"] = folderResult
            coroutineContext.ensureActive()
        } else {
            folderResult = null
        }

        // Build folder ID mappings after syncing folders.
        val syncedFolders =
            database.folderQueries
                .getByAccountId(accountId = user.id)
                .executeAsList()
                .map { it.data_ }
                .filterByAccountId(user.id) { it.accountId }
        val folderIdMappings =
            buildFolderIdMappings(
                accountId = user.id,
                folders = syncedFolders,
            )

        // --- Ciphers ---
        if (shouldSync(SyncEntityType.CIPHERS)) {
            if (folderResult != null) {
                requireFolderSyncCompletedBeforeCiphers(folderResult)
            }

            post(title = "Syncing cipher entities.")
            val existingCiphers =
                database.cipherQueries
                    .getByAccountId(accountId = user.id)
                    .executeAsList()
                    .map { it.data_ }
                    .filterByAccountId(user.id) { it.accountId }

            val encryptedFor =
                run {
                    require(newProfile.profileId.isNotBlank()) {
                        "Bitwarden profile id must be present before uploading cipher changes."
                    }
                    newProfile.profileId
                }
            val cipherOps =
                CipherSyncOps(
                    accountId = user.id,
                    db = database,
                    crypto = crypto,
                    cryptoGenerator = cryptoGenerator,
                    base64Service = base64Service,
                    getPasswordStrength = getPasswordStrength,
                    logRepository = logRepository,
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    ciphersApi = api.ciphers,
                    encryptedFor = encryptedFor,
                    remoteToLocalFolders = folderIdMappings.remoteToLocalFolders,
                    localToRemoteFolders = folderIdMappings.localToRemoteFolders,
                    serverFolders = response.folders.orEmpty(),
                    pendingUploadCoordinator = pendingUploadCoordinator,
                )
            val cipherResult =
                coordinator.safeSyncEntityType(
                    EntitySyncConfig(
                        name = "ciphers",
                        strategy = CipherSyncStrategy(),
                        localEntities = existingCiphers,
                        serverEntities = response.ciphers.orEmpty(),
                        ops = cipherOps,
                        bulkRemoteOps = cipherOps,
                    ),
                )
            outcomes["ciphers"] = cipherResult
            coroutineContext.ensureActive()
        }

        // --- Collections ---
        if (shouldSync(SyncEntityType.COLLECTIONS)) {
            post(title = "Syncing collection entities.")
            val existingCollections =
                database.collectionQueries
                    .getByAccountId(accountId = user.id)
                    .executeAsList()
                    .map { it.data_ }
                    .filterByAccountId(user.id) { it.accountId }
            val collectionOps =
                CollectionSyncOps(
                    accountId = user.id,
                    db = database,
                    crypto = crypto,
                )
            val collectionResult =
                coordinator.safeSyncEntityType(
                    EntitySyncConfig(
                        name = "collections",
                        strategy = CollectionSyncStrategy(),
                        localEntities = existingCollections,
                        serverEntities = response.collections.orEmpty(),
                        ops = collectionOps,
                    ),
                )
            outcomes["collections"] = collectionResult
            coroutineContext.ensureActive()
        }

        // --- Organizations ---
        if (shouldSync(SyncEntityType.ORGANIZATIONS)) {
            post(title = "Syncing organization entities.")
            val existingOrganizations =
                database.organizationQueries
                    .getByAccountId(accountId = user.id)
                    .executeAsList()
                    .map { it.data_ }
                    .filterByAccountId(user.id) { it.accountId }
            val orgCodec = getCodec(crypto, BitwardenCrCta.Mode.DECRYPT)
            val organizationOps =
                OrganizationSyncOps(
                    accountId = user.id,
                    db = database,
                    codec = orgCodec,
                )
            val orgResult =
                coordinator.safeSyncEntityType(
                    EntitySyncConfig(
                        name = "organizations",
                        strategy = OrganizationSyncStrategy(),
                        localEntities = existingOrganizations,
                        serverEntities = response.profile.organizations.orEmpty(),
                        ops = organizationOps,
                    ),
                )
            outcomes["organizations"] = orgResult
            coroutineContext.ensureActive()
        }

        // --- Sends ---
        if (shouldSync(SyncEntityType.SENDS)) {
            post(title = "Syncing send entities.")
            val existingSends =
                database.sendQueries
                    .getByAccountId(accountId = user.id)
                    .executeAsList()
                    .map { it.data_ }
                    .filterByAccountId(user.id) { it.accountId }
            val sendOps =
                SendSyncOps(
                    accountId = user.id,
                    db = database,
                    crypto = crypto,
                    cryptoGenerator = cryptoGenerator,
                    base64Service = base64Service,
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    sendsApi = api.sends,
                    pendingUploadCoordinator = pendingUploadCoordinator,
                )
            val sendResult =
                coordinator.safeSyncEntityType(
                    EntitySyncConfig(
                        name = "sends",
                        strategy = SendSyncStrategy(),
                        localEntities = existingSends,
                        serverEntities = response.sends.orEmpty(),
                        ops = sendOps,
                    ),
                )
            outcomes["sends"] = sendResult
            coroutineContext.ensureActive()
        }

        // --- Aggregate results and log ---
        val syncResult = SyncResult(outcomes = outcomes)
        logSyncResult(syncResult)
        syncResult.requireCleanForRevisionCache()

        if (serverRevisionDate != null) {
            val metaDao = database.metaQueries
            val existingMeta =
                metaDao
                    .getByAccountId(user.id)
                    .executeAsList()
                    .firstOrNull()
                    ?.data_
            metaDao.insert(
                accountId = user.id,
                data =
                    (existingMeta ?: BitwardenMeta(accountId = user.id)).copy(
                        lastServerRevisionDate = serverRevisionDate,
                        lastSyncServiceVersion = BitwardenService.VERSION,
                    ),
            )
        }

        post(title = "Syncing complete.")
    }

    /**
     * Determines whether the full `GET /sync` can be skipped.
     *
     * Two-pass check: first without the expensive local scan
     * (quick reject), then with it if the revision date matches.
     */
    private fun shouldSkipFullSync(
        accountId: String,
        database: Database,
        serverRevisionDate: String?,
    ): Boolean {
        val existingMeta =
            database.metaQueries
                .getByAccountId(accountId)
                .executeAsList()
                .firstOrNull()
                ?.data_

        val canSkipWithoutLocalCheck =
            shouldSkipFullSyncForRevision(
                existingMeta = existingMeta,
                serverRevisionDate = serverRevisionDate,
                hasPendingLocalWork = false,
            )
        if (!canSkipWithoutLocalCheck) return false

        return shouldSkipFullSyncForRevision(
            existingMeta = existingMeta,
            serverRevisionDate = serverRevisionDate,
            hasPendingLocalWork = hasPendingLocalWork(accountId, database),
        )
    }

    /**
     * Scans all entity types for entities that need syncing:
     * retryable errors, pending local changes (new, modified,
     * or deleted items). If any exist, the revision-date skip
     * is suppressed so that sync can process them.
     */
    private fun hasPendingLocalWork(
        accountId: String,
        database: Database,
    ): Boolean =
        hasPendingLocalWork(
            ciphers = {
                database.cipherQueries
                    .getByAccountId(accountId = accountId)
                    .executeAsList()
                    .asSequence()
                    .map { it.data_ }
                    .filter { it.accountId == accountId }
            },
            folders = {
                database.folderQueries
                    .getByAccountId(accountId = accountId)
                    .executeAsList()
                    .asSequence()
                    .map { it.data_ }
                    .filter { it.accountId == accountId }
            },
            sends = {
                database.sendQueries
                    .getByAccountId(accountId = accountId)
                    .executeAsList()
                    .asSequence()
                    .map { it.data_ }
                    .filter { it.accountId == accountId }
            },
            collections = {
                database.collectionQueries
                    .getByAccountId(accountId = accountId)
                    .executeAsList()
                    .asSequence()
                    .map { it.data_ }
                    .filter { it.accountId == accountId }
            },
            organizations = {
                database.organizationQueries
                    .getByAccountId(accountId = accountId)
                    .executeAsList()
                    .asSequence()
                    .map { it.data_ }
                    .filter { it.accountId == accountId }
            },
        )

    // ---------------------------------------------------------------
    // Profile sync (inline)
    // ---------------------------------------------------------------

    /**
     * Syncs the user profile inline (not via the entity pipeline).
     * Verifies the security stamp has not changed (MITM detection)
     * and merges the remote profile with any local overrides.
     */
    private suspend fun syncProfile(
        database: Database,
        user: BitwardenToken,
        response: SyncEntity,
        crypto: BitwardenCr,
    ): BitwardenProfile {
        val codec = getCodec(crypto, BitwardenCrCta.Mode.DECRYPT)
        val remoteProfile =
            BitwardenProfile
                .encrypted(
                    accountId = user.id,
                    entity = response.profile,
                    unofficialServer = response.unofficialServer == true,
                ).transform(crypto = codec)

        return dbSyncer.withLock(DatabaseSyncer.Key.Profile(response.profile.id)) {
            val profileDao = database.profileQueries
            val existingProfile =
                profileDao
                    .getByAccountId(accountId = user.id)
                    .executeAsOneOrNull()
            requireTrustedProfileId(
                existingProfileId = existingProfile?.profileId,
                remoteProfileId = response.profile.id,
            )
            requireTrustedProfileSecurityStamp(
                existingSecurityStamp = existingProfile?.data_?.securityStamp,
                remoteSecurityStamp = response.profile.securityStamp,
            )

            val newProfile =
                merge(
                    remote = remoteProfile,
                    local = existingProfile?.data_,
                )
            if (newProfile != existingProfile?.data_) {
                profileDao.insert(
                    profileId = newProfile.profileId,
                    accountId = newProfile.accountId,
                    data = newProfile,
                )
            }
            newProfile
        }
    }

    private fun requireTrustedProfile(
        database: Database,
        user: BitwardenToken,
        response: SyncEntity,
    ) {
        val existingProfile =
            database.profileQueries
                .getByAccountId(accountId = user.id)
                .executeAsOneOrNull()
        requireTrustedProfileId(
            existingProfileId = existingProfile?.profileId,
            remoteProfileId = response.profile.id,
        )
        requireTrustedProfileSecurityStamp(
            existingSecurityStamp = existingProfile?.data_?.securityStamp,
            remoteSecurityStamp = response.profile.securityStamp,
        )
    }

    // ---------------------------------------------------------------
    // Equivalent domains sync (inline)
    // ---------------------------------------------------------------

    /**
     * Syncs equivalent domains inline (full replace).
     * All existing entries for the account are deleted and
     * re-inserted from the sync response.
     */
    private fun syncEquivalentDomains(
        database: Database,
        user: BitwardenToken,
        response: SyncEntity,
        crypto: BitwardenCr,
    ) {
        val codec = getCodec(crypto, BitwardenCrCta.Mode.DECRYPT)
        val equivalentDomains =
            kotlin.run {
                val data = response.domains ?: return@run emptyList()

                val customEqDomains =
                    data.equivalentDomains
                        .orEmpty()
                        .map { domains ->
                            val entryId = cryptoGenerator.uuid()
                            BitwardenEquivalentDomain
                                .encrypted(
                                    accountId = user.id,
                                    entryId = entryId,
                                    domains = domains,
                                ).transform(codec)
                        }
                val globalEqDomains =
                    data.globalEquivalentDomains
                        .orEmpty()
                        .map { entity ->
                            val entryId = cryptoGenerator.uuid()
                            BitwardenEquivalentDomain
                                .encrypted(
                                    accountId = user.id,
                                    entryId = entryId,
                                    entity = entity,
                                ).transform(codec)
                        }
                customEqDomains + globalEqDomains
            }
        val equivalentDomainsDao = database.equivalentDomainsQueries
        equivalentDomainsDao.transaction {
            equivalentDomainsDao.deleteByAccountId(accountId = user.id)
            equivalentDomains.forEach { data ->
                equivalentDomainsDao.insert(
                    data = data,
                    accountId = user.id,
                    entryId = data.entryId,
                )
            }
        }
    }

    // ---------------------------------------------------------------
    // Empty vault check
    // ---------------------------------------------------------------

    /**
     * Logs a diagnostic exception if the server returns an empty
     * cipher/folder list while the local database has existing ciphers.
     * This can indicate a server-side data loss or misconfiguration.
     */
    private fun checkForEmptyVault(
        database: Database,
        user: BitwardenToken,
        env: com.artemchep.keyguard.provider.bitwarden.ServerEnv,
        response: SyncEntity,
    ) {
        if (
            response.ciphers.isNullOrEmpty() &&
            response.folders.isNullOrEmpty()
        ) {
            val existingCiphers =
                database.cipherQueries
                    .getByAccountId(user.id)
                    .executeAsList()
            if (existingCiphers.isNotEmpty()) {
                val isSelfHosted = env.webVaultUrl.isNotBlank()
                val isUnofficial = response.unofficialServer == true

                val e =
                    suspiciousEmptySyncExceptionOrNull(
                        existingCipherCount = existingCiphers.size,
                        remoteCiphersEmpty = true,
                        remoteFoldersEmpty = true,
                        isSelfHosted = isSelfHosted,
                        isUnofficial = isUnofficial,
                    )
                        ?: return
                recordException(e)
                throw e
            }
        }
    }

    // ---------------------------------------------------------------
    // Crypto
    // ---------------------------------------------------------------

    /**
     * Builds the crypto context for this sync session: user token,
     * profile token (symmetric + asymmetric keys), and all
     * organization tokens from the profile response.
     */
    private fun buildCrypto(
        user: BitwardenToken,
        profile: ProfileEntity,
    ): BitwardenCr {
        val builder =
            BitwardenCrImpl(
                cipherEncryptor = cipherEncryptor,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            ).apply {
                appendUserToken(
                    encKey = base64Service.decode(user.key.encryptionKeyBase64),
                    macKey = base64Service.decode(user.key.macKeyBase64),
                )
                appendProfileToken(
                    keyCipherText = profile.key,
                    privateKeyCipherText = profile.privateKey,
                )
                profile.organizations.orEmpty().forEach { organization ->
                    appendOrganizationToken(
                        id = organization.id,
                        keyCipherText = organization.key,
                    )
                }
            }
        return builder.build()
    }

    private fun getCodec(
        crypto: BitwardenCr,
        mode: BitwardenCrCta.Mode,
        organizationId: String? = null,
    ): BitwardenCrCta {
        val envEncryptionType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64
        val key =
            if (organizationId != null) {
                BitwardenCrKey.OrganizationToken(organizationId)
            } else {
                BitwardenCrKey.UserToken
            }
        val env =
            BitwardenCrCta.BitwardenCrCtaEnv(
                key = key,
                encryptionType = envEncryptionType,
            )
        return crypto.cta(
            env = env,
            mode = mode,
        )
    }

    // ---------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------

    private suspend fun logSyncResult(result: SyncResult) {
        val message = buildString {
            append("V2 sync completed: ")
            append("${result.totalSucceeded} succeeded, ")
            append("${result.totalSkipped} skipped, ")
            append("${result.totalActionFailures} action failures, ")
            append("${result.totalEntityTypeFailures} entity type failures")
            if (!result.isFullySuccessful) {
                val failedTypes =
                    result.outcomes.entries
                        .filter { it.value is EntityTypeOutcome.Failed }
                        .joinToString { it.key }
                append(" [failed: $failedTypes]")
            }
        }
        logRepository.add(TAG, message, LogLevel.INFO)
    }
}

/**
 * Pure logic for the revision-date skip check.
 *
 * Returns `true` (skip) only when all conditions hold:
 * - Server revision date is available.
 * - Local metadata exists with a stored revision date.
 * - Service version matches [BitwardenService.VERSION].
 * - Last sync was successful.
 * - No pending local work exists (retryable errors or unsyncedchanges).
 * - Server revision date equals the stored one.
 */
internal fun shouldSkipFullSyncForRevision(
    existingMeta: BitwardenMeta?,
    serverRevisionDate: String?,
    hasPendingLocalWork: Boolean,
): Boolean {
    if (serverRevisionDate == null) return false

    existingMeta ?: return false
    val storedRevisionDate = existingMeta.lastServerRevisionDate ?: return false
    if (existingMeta.lastSyncServiceVersion != BitwardenService.VERSION) return false
    if (existingMeta.lastSyncResult is BitwardenMeta.LastSyncResult.Failure) return false
    if (hasPendingLocalWork) return false

    return serverRevisionDate == storedRevisionDate
}

internal fun requiresAuthenticationForSyncFailure(e: Throwable): Boolean =
    generateSequence(e) { it.cause }
        .any {
            it.hasHttpStatusCode(
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden,
            )
        }

internal class UntrustedProfileException(
    message: String,
) : IllegalStateException(message)

internal fun requireTrustedProfileId(
    existingProfileId: String?,
    remoteProfileId: String,
) {
    if (existingProfileId == null) return
    if (existingProfileId != remoteProfileId) {
        throw UntrustedProfileException(
            "Local profile id differs from a remote one. " +
                "You might be in a man-in-the-middle attack!",
        )
    }
}

internal fun requireTrustedProfileSecurityStamp(
    existingSecurityStamp: String?,
    remoteSecurityStamp: String,
) {
    if (existingSecurityStamp == null) return
    if (existingSecurityStamp != remoteSecurityStamp) {
        throw UntrustedProfileException(
            "Local security stamp differs from a remote one. " +
                "You might be in a man-in-the-middle attack!",
        )
    }
}

internal fun suspiciousEmptySyncExceptionOrNull(
    existingCipherCount: Int,
    remoteCiphersEmpty: Boolean,
    remoteFoldersEmpty: Boolean,
    isSelfHosted: Boolean,
    isUnofficial: Boolean,
): SyncEngine.EmptyVaultException? {
    if (existingCipherCount <= 0) return null
    if (!remoteCiphersEmpty || !remoteFoldersEmpty) return null

    val message =
        "Backend returned empty cipher list, while there's " +
            "$existingCipherCount ciphers in the local storage: " +
            "official=${!isUnofficial}, self-hosted=$isSelfHosted"
    return SyncEngine.EmptyVaultException(message)
}

/**
 * Fetches the server's revision date string, returning `null` on
 * any non-cancellation error (the endpoint may not be supported
 * by all server implementations).
 */
internal suspend fun fetchServerRevisionDateOrNull(
    fetch: suspend () -> String,
): String? = try {
    fetch()
} catch (e: Exception) {
    e.throwIfCancellation()
    null
}

/**
 * Checks whether any entity across all types needs syncing:
 * either a retryable error, or pending local changes (new items,
 * modified items, or locally deleted items). Used to suppress the
 * revision-date skip so that the sync pipeline can process them.
 */
internal fun hasPendingLocalWork(
    ciphers: List<BitwardenCipher>,
    folders: List<BitwardenFolder>,
    sends: List<BitwardenSend>,
    collections: List<BitwardenCollection>,
    organizations: List<BitwardenOrganization>,
): Boolean =
    hasPendingLocalWork(
        ciphers = { ciphers.asSequence() },
        folders = { folders.asSequence() },
        sends = { sends.asSequence() },
        collections = { collections.asSequence() },
        organizations = { organizations.asSequence() },
    )

internal fun hasPendingLocalWork(
    ciphers: () -> Sequence<BitwardenCipher>,
    folders: () -> Sequence<BitwardenFolder>,
    sends: () -> Sequence<BitwardenSend>,
    collections: () -> Sequence<BitwardenCollection>,
    organizations: () -> Sequence<BitwardenOrganization>,
): Boolean {
    if (ciphers().anyPendingCipherLocalWork()) return true
    if (folders().anyPendingFolderLocalWork()) return true
    if (sends().anyPendingSendLocalWork()) return true
    if (collections().anyPendingCollectionLocalWork()) return true
    return organizations().anyPendingOrganizationLocalWork()
}

internal fun LocalItemMeta.hasPendingLocalWork(): Boolean {
    if (hasError && canRetryError) return true
    if (isLocallyDeleted) return true
    if (remoteId == null) return true
    if (revisionDate != lastSyncedRevisionDate) return true
    if (deletedDate != lastSyncedDeletedDate) return true
    if (serviceVersion != BitwardenService.VERSION) return true
    if (requiresLocalRefreshWhenDatesMatch) return true
    if (requiresPushWhenDatesMatch) return true
    if (requiresForcePushWhenDatesMatch) return true
    return false
}

internal fun Sequence<BitwardenCipher>.anyPendingCipherLocalWork(): Boolean {
    val cipherStrategy = CipherSyncStrategy()
    return any { cipher ->
        cipher.hasPendingAttachmentMutations() ||
            cipherStrategy.toLocalItemMeta(cipher).hasPendingLocalWork()
    }
}

internal fun Sequence<BitwardenFolder>.anyPendingFolderLocalWork(): Boolean {
    val folderStrategy = FolderSyncStrategy()
    return any { folder ->
        folderStrategy.toLocalItemMeta(folder).hasPendingLocalWork()
    }
}

internal fun Sequence<BitwardenSend>.anyPendingSendLocalWork(): Boolean {
    val sendStrategy = SendSyncStrategy()
    return any { send ->
        send.hasPendingFileUpload() ||
            sendStrategy.toLocalItemMeta(send).hasPendingLocalWork()
    }
}

internal fun Sequence<BitwardenCollection>.anyPendingCollectionLocalWork(): Boolean {
    val collectionStrategy = CollectionSyncStrategy()
    return any { collection ->
        collectionStrategy.toLocalItemMeta(collection).hasPendingLocalWork()
    }
}

internal fun Sequence<BitwardenOrganization>.anyPendingOrganizationLocalWork(): Boolean {
    val organizationStrategy = OrganizationSyncStrategy()
    return any { organization ->
        organizationStrategy.toLocalItemMeta(organization).hasPendingLocalWork()
    }
}
