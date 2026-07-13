package com.artemchep.keyguard.provider.bitwarden.usecase

import app.cash.sqldelight.coroutines.asFlow
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.CipherSnapshot
import com.artemchep.keyguard.common.usecase.GetCipherSnapshots
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.withLogTimeOfFirstEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

/**
 * @author Artem Chepurnyi
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GetCipherSnapshotsImpl(
    private val logRepository: LogRepository,
    private val databaseManager: VaultDatabaseManager,
    private val getPasswordStrength: GetPasswordStrength,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dbDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : GetCipherSnapshots {
    companion object {
        private const val TAG = "GetCipherSnapshots.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        databaseManager = directDI.instance(),
        getPasswordStrength = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
        dbDispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    private val sharedFlow = databaseManager
        .get()
        .asFlow()
        .flatMapLatest { db ->
            var snapshotsByCipherId = emptyMap<String, CipherSnapshot>()
            val loader = CipherSnapshotLoader(
                dbDispatcher = dbDispatcher,
                getPasswordStrength = getPasswordStrength,
            )
            db.cipherQueries
                .getCipherSnapshotKeys()
                .asFlow()
                .map {
                    val result = loader.load(
                        db = db,
                        previousSnapshotsByCipherId = snapshotsByCipherId,
                    )
                    snapshotsByCipherId = result.snapshotsByCipherId
                    result.snapshots
                }
        }
        .withLogTimeOfFirstEvent(logRepository, TAG)
        .flowOn(defaultDispatcher)
        .shareIn(
            scope = windowCoroutineScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 5000L,
                replayExpirationMillis = 0L,
            ),
            replay = 1,
        )

    override fun invoke(): Flow<List<CipherSnapshot>> = sharedFlow
}
