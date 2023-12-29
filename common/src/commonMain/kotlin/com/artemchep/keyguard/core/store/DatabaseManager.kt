package com.artemchep.keyguard.core.store

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.retry
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.data.CipherUsageHistory
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.GeneratorEmailRelay
import com.artemchep.keyguard.data.GeneratorHistory
import com.artemchep.keyguard.data.bitwarden.Account
import com.artemchep.keyguard.data.bitwarden.Cipher
import com.artemchep.keyguard.data.bitwarden.Collection
import com.artemchep.keyguard.data.bitwarden.Folder
import com.artemchep.keyguard.data.bitwarden.Meta
import com.artemchep.keyguard.data.bitwarden.Organization
import com.artemchep.keyguard.data.bitwarden.Profile
import com.artemchep.keyguard.data.bitwarden.Send
import com.artemchep.keyguard.data.pwnage.AccountBreach
import com.artemchep.keyguard.data.pwnage.PasswordBreach
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

interface DatabaseManager {
    fun get(): IO<Database>

    fun <T> mutate(
        block: suspend (Database) -> T,
    ): IO<T>

    /**
     * Changes the password of the database. After executing the function, you must create a
     * new database manager with a new master key.
     */
    fun changePassword(newMasterKey: MasterKey): IO<Unit>
}

interface SqlManager {
    fun create(
        masterKey: MasterKey,
        databaseFactory: (SqlDriver) -> Database,
    ): IO<SqlHelper>
}

interface SqlHelper {
    val driver: SqlDriver

    val database: Database

    /**
     * Changes the password of the database. After executing the function, you should create a
     * new database manager with a new master key.
     */
    fun changePassword(newMasterKey: MasterKey): IO<Unit>
}

class DatabaseManagerImpl(
    private val logRepository: LogRepository,
    private val json: Json,
    private val sqlManager: SqlManager,
    masterKey: MasterKey,
) : DatabaseManager {
    companion object {
        private const val TAG = "DatabaseManager"

        private val mutex = Mutex()
    }

    private val bitwardenCipherToStringAdapter = BitwardenCipherToStringAdapter(json)
    private val bitwardenSendToStringAdapter = BitwardenSendToStringAdapter(json)
    private val bitwardenCollectionToStringAdapter = BitwardenCollectionToStringAdapter(json)
    private val bitwardenFolderToStringAdapter = BitwardenFolderToStringAdapter(json)
    private val bitwardenMetaToStringAdapter = BitwardenMetaToStringAdapter(json)
    private val bitwardenOrganizationToStringAdapter = BitwardenOrganizationToStringAdapter(json)
    private val bitwardenProfileToStringAdapter = BitwardenProfileToStringAdapter(json)
    private val bitwardenTokenToStringAdapter = BitwardenTokenToStringAdapter(json)
    private val hibpAccountBreachToStringAdapter = HibpAccountBreachToStringAdapter(json)

    private val dbIo = io(masterKey)
        .effectMap { masterKey ->
            val databaseFactory = { driver: SqlDriver ->
                Database(
                    driver = driver,
                    cipherUsageHistoryAdapter = CipherUsageHistory.Adapter(InstantToLongAdapter),
                    generatorHistoryAdapter = GeneratorHistory.Adapter(InstantToLongAdapter),
                    generatorEmailRelayAdapter = GeneratorEmailRelay.Adapter(InstantToLongAdapter),
                    cipherAdapter = Cipher.Adapter(bitwardenCipherToStringAdapter),
                    sendAdapter = Send.Adapter(bitwardenSendToStringAdapter),
                    collectionAdapter = Collection.Adapter(bitwardenCollectionToStringAdapter),
                    folderAdapter = Folder.Adapter(bitwardenFolderToStringAdapter),
                    metaAdapter = Meta.Adapter(bitwardenMetaToStringAdapter),
                    organizationAdapter = Organization.Adapter(bitwardenOrganizationToStringAdapter),
                    profileAdapter = Profile.Adapter(bitwardenProfileToStringAdapter),
                    accountAdapter = Account.Adapter(bitwardenTokenToStringAdapter),
                    passwordBreachAdapter = PasswordBreach.Adapter(
                        updatedAtAdapter = InstantToLongAdapter,
                    ),
                    accountBreachAdapter = AccountBreach.Adapter(
                        updatedAtAdapter = InstantToLongAdapter,
                        data_Adapter = hibpAccountBreachToStringAdapter,
                    ),
                )
            }
            val sqlHelper = sqlManager
                .create(masterKey, databaseFactory)
                .bind()
            sqlHelper
        }
        .retry { e, attempt ->
            e.printStackTrace()
            false
        }
        .shared()

    override fun get() = dbIo.map { it.database }

    override fun <T> mutate(
        block: suspend (Database) -> T,
    ) = dbIo
        .effectMap(Dispatchers.IO) { db ->
            mutex.withLock {
                block(db.database)
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
}

private object InstantToLongAdapter : ColumnAdapter<Instant, Long> {
    override fun decode(databaseValue: Long) = Instant.fromEpochMilliseconds(databaseValue)

    override fun encode(value: Instant) = value.toEpochMilliseconds()
}

private class BitwardenCipherToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenCipher, String> by ObjectToStringAdapter(json)

private class BitwardenSendToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenSend, String> by ObjectToStringAdapter(json)

private class BitwardenCollectionToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenCollection, String> by ObjectToStringAdapter(json)

private class BitwardenFolderToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenFolder, String> by ObjectToStringAdapter(json)

private class BitwardenMetaToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenMeta, String> by ObjectToStringAdapter(json)

private class BitwardenOrganizationToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenOrganization, String> by ObjectToStringAdapter(json)

private class BitwardenProfileToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenProfile, String> by ObjectToStringAdapter(json)

private class BitwardenTokenToStringAdapter(
    private val json: Json,
) : ColumnAdapter<BitwardenToken, String> by ObjectToStringAdapter(json)

private class HibpAccountBreachToStringAdapter(
    private val json: Json,
) : ColumnAdapter<HibpBreachGroup, String> by ObjectToStringAdapter(json)

private class ObjectToStringAdapter<T : Any>(
    private val serializer: KSerializer<T>,
    private val json: Json,
) : ColumnAdapter<T, String> {
    companion object {
        @OptIn(InternalSerializationApi::class)
        inline operator fun <reified T : Any> invoke(
            json: Json,
        ) = ObjectToStringAdapter(
            serializer = T::class.serializer(),
            json = json,
        )
    }

    override fun decode(databaseValue: String) = json.decodeFromString(serializer, databaseValue)

    override fun encode(value: T) = json.encodeToString(serializer, value)
}
