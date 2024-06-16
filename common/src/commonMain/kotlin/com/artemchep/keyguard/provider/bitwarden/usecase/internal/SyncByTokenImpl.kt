package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import app.cash.sqldelight.coroutines.asFlow
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.biFlatTap
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.SyncProgress
import com.artemchep.keyguard.common.model.SyncScope
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.api.SyncEngine
import com.artemchep.keyguard.provider.bitwarden.usecase.util.withRefreshableAccessToken
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class SyncByTokenImpl(
    private val logRepository: LogRepository,
    private val cipherEncryptor: CipherEncryptor,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val getPasswordStrength: GetPasswordStrength,
    private val json: Json,
    private val httpClient: HttpClient,
    private val db: DatabaseManager,
    private val dbSyncer: DatabaseSyncer,
    private val watchdog: Watchdog,
) : SyncByToken {
    companion object {
        private const val TAG = "SyncById.bitwarden"
    }

    private val mutex = Mutex()

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
        watchdog = directDI.instance(),
    )

    override fun invoke(user: BitwardenToken): IO<Unit> = watchdog
        .track(
            accountId = AccountId(user.id),
            accountTask = AccountTask.SYNC,
        ) {
            val scope = object : SyncScope {
                override suspend fun post(
                    title: String,
                    progress: SyncProgress.Progress?,
                ) {
                    logRepository.add(TAG, title)
                }
            }
            // We want to automatically request a new access token if the old
            // one has expired.
            withRefreshableAccessToken(
                base64Service = base64Service,
                httpClient = httpClient,
                json = json,
                db = db,
                user = user,
            ) { latestUser ->
                val syncEngine = SyncEngine(
                    httpClient = httpClient,
                    dbManager = db,
                    json = json,
                    base64Service = base64Service,
                    cryptoGenerator = cryptoGenerator,
                    cipherEncryptor = cipherEncryptor,
                    logRepository = logRepository,
                    getPasswordStrength = getPasswordStrength,
                    user = latestUser,
                    syncer = dbSyncer,
                )
                mutex.withLock {
                    with(scope) {
                        syncEngine.sync()
                    }
                }
//                sss(
//                    logRepository = logRepository,
//                    cipherEncryptor = cipherEncryptor,
//                    cryptoGenerator = cryptoGenerator,
//                    base64Service = base64Service,
//                    httpClient = httpClient,
//                    db = db,
//                    user = latestUser,
//                )
            }
        }
        .biFlatTap(
            ifException = { e ->
                db.mutate(TAG) {
                    val dao = it.metaQueries
                    val existingMeta = dao
                        .getByAccountId(accountId = user.id)
                        .asFlow()
                        .map { it.executeAsList().firstOrNull()?.data_ }
                        .toIO()
                        .bind()

                    val reason = e.localizedMessage ?: e.message
                    val requiresAuthentication = e is HttpException &&
                            (
                                    e.statusCode == HttpStatusCode.Unauthorized ||
                                            e.statusCode == HttpStatusCode.Forbidden
                                    )

                    val now = Clock.System.now()
                    val meta = BitwardenMeta(
                        accountId = user.id,
                        // Copy the existing successful sync timestamp
                        // into a new model.
                        lastSyncTimestamp = existingMeta?.lastSyncTimestamp,
                        lastSyncResult = BitwardenMeta.LastSyncResult.Failure(
                            timestamp = now,
                            reason = reason,
                            requiresAuthentication = requiresAuthentication,
                        ),
                    )

                    dao.insert(
                        accountId = user.id,
                        data = meta,
                    )
                }
            },
            ifSuccess = {
                db.mutate(TAG) {
                    val now = Clock.System.now()
                    val meta = BitwardenMeta(
                        accountId = user.id,
                        lastSyncTimestamp = now,
                        lastSyncResult = BitwardenMeta.LastSyncResult.Success,
                    )

                    val dao = it.metaQueries
                    dao.insert(
                        accountId = user.id,
                        data = meta,
                    )
                }
            },
        )
        .measure { duration, _ ->
            val message = "Synced user ${user.formatUser()} in $duration."
            logRepository.post(TAG, message)
        }
}
