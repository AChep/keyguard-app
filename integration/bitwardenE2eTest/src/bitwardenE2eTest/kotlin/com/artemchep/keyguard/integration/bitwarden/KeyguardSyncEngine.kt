package com.artemchep.keyguard.integration.bitwarden

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.DatabaseSqlHelper
import com.artemchep.keyguard.common.service.database.DatabaseSqlManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManagerImpl
import com.artemchep.keyguard.core.store.DatabaseSqlManagerInFileJvm
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.SyncByBitwardenTokenV2Impl
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock

/**
 * Keyguard's production Bitwarden sync engine on a fresh encrypted vault database,
 * with the account of a [KeyguardSession]. Close it to delete the database.
 */
internal class KeyguardSyncEngine private constructor(
    private val directory: File,
    private val sqlManager: ClosingSqlManager,
    initialToken: BitwardenToken,
    private val db: VaultDatabaseManager,
    private val engine: SyncByBitwardenTokenV2Impl,
) : AutoCloseable {
    /** The account row as of the last [sync]; a sync may refresh the access token. */
    var token: BitwardenToken = initialToken
        private set

    private val accountId = initialToken.id

    /** One sync run: the revision date check, then the full sync if the server changed. */
    suspend fun sync() {
        engine(token).invoke()
        token = storedToken()
    }

    suspend fun storedToken(): BitwardenToken = read { database ->
        database.accountQueries
            .getByAccountId(accountId)
            .executeAsOne()
            .data_ as BitwardenToken
    }

    suspend fun ciphers(): List<BitwardenCipher> = read { database ->
        database.cipherQueries
            .getByAccountId(accountId)
            .executeAsList()
            .map { it.data_ }
    }

    suspend fun folders(): List<BitwardenFolder> = read { database ->
        database.folderQueries
            .getByAccountId(accountId)
            .executeAsList()
            .map { it.data_ }
    }

    /** Writes [cipher] as is. A cipher without `service.remote` is a local item to upload. */
    suspend fun insertCipher(cipher: BitwardenCipher) {
        db.mutate(TAG) { database ->
            database.cipherQueries.insert(
                cipherId = cipher.cipherId,
                accountId = cipher.accountId,
                folderId = cipher.folderId,
                data = cipher,
                updatedAt = cipher.revisionDate,
            )
        }.bind()
    }

    /** Writes [cipher] with the revision date set to now, as Keyguard's own edits do. */
    suspend fun updateCipher(cipher: BitwardenCipher) {
        insertCipher(cipher.copy(revisionDate = Clock.System.now()))
    }

    /** Writes [token] as the account row and starts the next [sync] with it, as if the app had stored it. */
    suspend fun replaceToken(token: BitwardenToken) {
        require(token.id == accountId) { "The token belongs to another account" }
        db.mutate(TAG) { database ->
            database.accountQueries.insert(
                accountId = token.id,
                data = token,
            )
        }.bind()
        this.token = token
    }

    override fun close() {
        sqlManager.close()
        directory.deleteRecursively()
    }

    private suspend fun <T> read(block: (Database) -> T): T = block(db.get().bind())

    companion object {
        private const val TAG = "e2e"
        private const val MASTER_KEY_LENGTH = 32

        @Suppress("TooGenericExceptionCaught")
        suspend fun create(fixture: VaultwardenFixture, session: KeyguardSession): KeyguardSyncEngine {
            val directory = Files.createTempDirectory("keyguard-e2e-vault").toFile()
            val sqlManager = ClosingSqlManager(
                DatabaseSqlManagerInFileJvm(fileIo = io(File(directory, "database.sqlite"))),
            )
            val db = VaultDatabaseManagerImpl(
                logRepository = NoopLogRepository,
                json = fixture.json,
                sqlManager = sqlManager,
                masterKey = MasterKey(
                    version = MasterKdfVersion.LATEST,
                    byteArray = fixture.cryptoGenerator.seed(MASTER_KEY_LENGTH),
                ),
            )
            val engine = SyncByBitwardenTokenV2Impl(
                logRepository = NoopLogRepository,
                cipherEncryptor = fixture.cipherEncryptor,
                cryptoGenerator = fixture.cryptoGenerator,
                base64Service = fixture.base64Service,
                getPasswordStrength = ConstantPasswordStrength,
                json = fixture.json,
                httpClient = fixture.httpClient,
                db = db,
                dbSyncer = DatabaseSyncer(fixture.cryptoGenerator),
                pendingUploadCoordinator = FailingPendingUploadCoordinator,
                watchdog = NoopWatchdog,
                markBackupAsDirty = NoopMarkBackupAsDirty,
                gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
            )
            val syncEngine = KeyguardSyncEngine(
                directory = directory,
                sqlManager = sqlManager,
                initialToken = session.token,
                db = db,
                engine = engine,
            )
            try {
                syncEngine.replaceToken(session.token)
            } catch (e: Throwable) {
                syncEngine.close()
                throw e
            }
            return syncEngine
        }
    }
}

/** Keeps the helper of the database it creates, so the driver can be closed. */
private class ClosingSqlManager(
    private val delegate: DatabaseSqlManager<Database>,
) : DatabaseSqlManager<Database> {
    @Volatile
    private var helper: DatabaseSqlHelper<Database>? = null

    override fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
        databaseSchema: SqlSchema<QueryResult.Value<Unit>>,
        vararg callbacks: AfterVersion,
    ): IO<DatabaseSqlHelper<Database>> = ioEffect {
        delegate
            .create(masterKey, databaseFactory, databaseSchema, *callbacks)
            .bind()
            .also { helper = it }
    }

    fun close() {
        helper?.driver?.close()
    }
}
