package com.artemchep.keyguard.common.service.database.exposed

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.SqlDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.retry
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterSaltUseCase
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ExposedDatabaseManagerImpl(
    private val logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val settingsRepository: SettingsReadWriteRepository,
    private val generateMasterKeyUseCase: GenerateMasterKeyUseCase,
    private val generateMasterHashUseCase: GenerateMasterHashUseCase,
    private val generateMasterSaltUseCase: GenerateMasterSaltUseCase,
    private val json: Json,
    private val sqlManager: DatabaseSqlManager<DatabaseExposed>,
) : ExposedDatabaseManager {
    companion object {
        private const val TAG = "ExposedDatabaseManager"

        /**
         * The migration that stopped mirroring account emails and server hosts.
         * Dropping their table leaves the old values in free pages, so the
         * database is vacuumed once that upgrade has committed.
         */
        private const val ACCOUNT_NAME_ONLY_VERSION = 6L

        private val mutex = Mutex()
    }

    private val dbIo = getOrCreateDatabaseMasterKeyIo()
        .effectMap { masterKey ->
            val databaseFactory = { driver: SqlDriver ->
                DatabaseExposed(
                    driver = driver,
                    urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter),
                )
            }
            // Migrations run inside a transaction, where VACUUM is not allowed,
            // so the callback only flags it to run after the upgrade commits.
            var vacuumAfterUpgrade = false
            val callbacks = arrayOf(
                AfterVersion(
                    afterVersion = ACCOUNT_NAME_ONLY_VERSION,
                ) {
                    vacuumAfterUpgrade = true
                },
            )
            val sqlHelper = sqlManager
                .create(masterKey, databaseFactory, DatabaseExposed.Schema, *callbacks)
                .bind()
            if (vacuumAfterUpgrade) {
                vacuum(sqlHelper.driver)
            }
            sqlHelper
        }
        .retry { e, attempt ->
            e.printStackTrace()
            false
        }
        .shared("dbIo")

    override fun get() = dbIo.map { it.database }

    private fun vacuum(driver: SqlDriver) {
        // Best effort: the old values are already unreachable through the schema,
        // and a failed rebuild must not keep the database from opening.
        runCatchingNonFatal {
            driver.execute(
                identifier = null,
                sql = "VACUUM",
                parameters = 0,
            )
        }.onFailure { e ->
            logRepository.post(
                tag = TAG,
                message = "Failed to vacuum the database: ${e.message}",
                level = LogLevel.ERROR,
            )
        }
    }

    override fun <T> mutate(
        tag: String,
        block: suspend (DatabaseExposed) -> T,
    ) = dbIo
        .effectMap(Dispatchers.IO) { db ->
            logRepository.add(
                tag = TAG,
                message = "Adding '$tag' database lock.",
            )
            mutex.lock()
            try {
                block(db.database)
            } finally {
                try {
                    withContext(NonCancellable) {
                        logRepository.add(
                            tag = TAG,
                            message = "Removing '$tag' database lock.",
                        )
                    }
                } finally {
                    mutex.unlock()
                }
            }
        }

    /**
     * Changes the password of the database. After executing the function, you must create a
     * new database manager with a new master key.
     */
    override fun changePassword(newMasterKey: MasterKey) = dbIo
        .effectMap(Dispatchers.IO) { db ->
            mutex.withLock {
                db.changePassword(newMasterKey)
                    .bind()
            }
        }

    //
    // Generate/get a master key
    //

    private fun getOrCreateDatabaseMasterKeyIo(
    ) = ioEffect {
        val existingMasterKeyBytes = settingsRepository.getExposedDatabaseKey()
            .first()
        if (existingMasterKeyBytes != null) {
            val masterKey = MasterKey(
                version = MasterKdfVersion.V0, // stay forever at v0, this database is public
                byteArray = existingMasterKeyBytes,
            )
            return@ioEffect masterKey
        }

        val masterKey = createDatabaseMasterKeyIo()
            .bind()
        // Save the key into the database, so the next time we get
        // the database key it gets retrieved from the settings instead.
        settingsRepository.setExposedDatabaseKey(masterKey.byteArray)
            .bind()
        return@ioEffect masterKey
    }

    /**
     * Creates a new database master key
     * for use in the database later.
     */
    private fun createDatabaseMasterKeyIo(
    ): IO<MasterKey> = ioEffect(Dispatchers.Default) {
        val masterPassword = kotlin.run {
            val data = cryptoGenerator.seed()
            MasterPassword(data)
        }
        val masterSalt = generateMasterSaltUseCase()
            .bind()
        val masterHash = generateMasterHashUseCase(
            masterPassword,
            masterSalt,
            MasterKdfVersion.V0, // stay forever at v0, this database is public
        )
            .bind()
        generateMasterKeyUseCase(masterPassword, masterHash)
            .bind()
    }
}
