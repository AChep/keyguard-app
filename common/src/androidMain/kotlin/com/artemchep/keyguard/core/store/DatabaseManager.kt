package com.artemchep.keyguard.core.store

import android.content.Context
import android.database.SQLException
import android.util.Log
import androidx.room.Room
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.retry
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.room.AppDatabase
import com.artemchep.keyguard.room.RoomSecretConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class DatabaseManagerAndroid(
    private val applicationContext: Context,
    private val json: Json,
    private val name: String,
    masterKey: MasterKey,
) {
    companion object {
        private const val TAG = "DatabaseManager"

        private val mutex = Mutex()
    }

    private val dbFileIo = ioEffect { applicationContext.getDatabasePath(name) }
        .shared()

    private val dbIo = io(masterKey)
        .effectMap { masterKey ->
            val databaseFile = dbFileIo
                .bind()
            if (!databaseFile.exists()) {
                return@effectMap null
            }
            // Create encrypted database using the provided master key. If the
            // key is incorrect, trying to open the database would lead to a crash.
            val factory = SupportOpenHelperFactory(masterKey.byteArray, null, false)
            Room
                .databaseBuilder(
                    applicationContext,
                    AppDatabase::class.java,
                    name,
                )
                .openHelperFactory(factory)
                .addTypeConverter(RoomSecretConverter(json))
                .build()
        }
        .retry { e, attempt ->
            e.printStackTrace()
            if (e is SQLException && attempt == 0) {
                applicationContext.deleteDatabase(name)
                return@retry true
            }

            false
        }
        .shared()

    fun migrateIfExists(
        sqld: Database,
    ): IO<Unit> = ioEffect(Dispatchers.IO) {
        val room = dbIo.bind()
        // Nothing to migrate.
            ?: return@ioEffect

        // account
        MigrationLogger.log("Account") {
            val accounts = room.accountDao().getAll().first()
            sqld.transaction {
                val accountIds = sqld.accountQueries
                    .getIds()
                    .executeAsList()
                    .toSet()
                accounts.forEach { account ->
                    if (account.accountId in accountIds) {
                        ignored()
                        return@forEach
                    }
                    sqld.accountQueries.insert(
                        accountId = account.accountId,
                        data = account.content,
                    )
                    changed()
                }
            }
        }
        // profile
        MigrationLogger.log("Profile") {
            val profiles = room.profileDao().getAll().first()
            sqld.transaction {
                val profileIds = sqld.profileQueries
                    .getIds()
                    .executeAsList()
                    .toSet()
                profiles.forEach { profile ->
                    if (profile.profileId in profileIds) {
                        ignored()
                        return@forEach
                    }
                    sqld.profileQueries.insert(
                        profileId = profile.profileId,
                        accountId = profile.accountId,
                        data = profile.content,
                    )
                    changed()
                }
            }
        }
        // meta
        MigrationLogger.log("Meta") {
            val metas = room.metaDao().getAll().first()
            sqld.transaction {
                val accountIds = sqld.metaQueries
                    .getIds()
                    .executeAsList()
                    .toSet()
                metas.forEach { meta ->
                    if (meta.accountId in accountIds) {
                        ignored()
                        return@forEach
                    }
                    sqld.metaQueries.insert(
                        accountId = meta.accountId,
                        data = meta.content,
                    )
                    changed()
                }
            }
        }
        // folder
        MigrationLogger.log("Folder") {
            val folders = room.folderDao().getAll().first()
            sqld.transaction {
                val folderIds = sqld.folderQueries
                    .getIds()
                    .executeAsList()
                    .toSet()
                folders.forEach { folder ->
                    if (folder.folderId in folderIds) {
                        ignored()
                        return@forEach
                    }
                    sqld.folderQueries.insert(
                        folderId = folder.folderId,
                        accountId = folder.accountId,
                        data = folder.content,
                    )
                    changed()
                }
            }
        }
        // org
        MigrationLogger.log("Organization") {
            val orgs = room.organizationDao().getAll().first()
            sqld.transaction {
                val organizationIds = sqld.organizationQueries
                    .getIds()
                    .executeAsList()
                    .toSet()
                orgs.forEach { org ->
                    if (org.organizationId in organizationIds) {
                        ignored()
                        return@forEach
                    }
                    sqld.organizationQueries.insert(
                        organizationId = org.organizationId,
                        accountId = org.accountId,
                        data = org.content,
                    )
                    changed()
                }
            }
        }
        // collection
        MigrationLogger.log("Collection") {
            val collections = room.collectionDao().getAll().first()
            sqld.transaction {
                val collectionIds = sqld.collectionQueries
                    .getIds()
                    .executeAsList()
                    .toSet()
                collections.forEach { collection ->
                    if (collection.collectionId in collectionIds) {
                        ignored()
                        return@forEach
                    }
                    sqld.collectionQueries.insert(
                        collectionId = collection.collectionId,
                        accountId = collection.accountId,
                        data = collection.content,
                    )
                    changed()
                }
            }
        }
        // cipher
        MigrationLogger.log("Cipher") {
            val ciphers = room.cipherDao().getAll().first()
            sqld.transaction {
                val cipherIds = sqld.cipherQueries
                    .getIds()
                    .executeAsList()
                    .toSet()
                ciphers.forEach { cipher ->
                    if (cipher.itemId in cipherIds) {
                        ignored()
                        return@forEach
                    }
                    sqld.cipherQueries.insert(
                        cipherId = cipher.itemId,
                        accountId = cipher.accountId,
                        folderId = cipher.folderId,
                        data = cipher.content,
                        updatedAt = cipher.content.revisionDate,
                    )
                    changed()
                }
            }
        }
        // history
        MigrationLogger.log("History") {
            val openCiphers = room.openCipherHistoryDao().getAll().first()
            val fillCiphers = room.fillCipherHistoryDao().getAll().first()
            sqld.transaction {
                val hasExistingHistory = sqld.cipherUsageHistoryQueries
                    .get(1)
                    .executeAsList()
                    .isNotEmpty()
                if (hasExistingHistory) {
                    return@transaction
                }

                openCiphers.forEach { openCipher ->
                    sqld.cipherUsageHistoryQueries.insert(
                        cipherId = openCipher.itemId,
                        credentialId = null,
                        createdAt = openCipher.timestamp
                            .let(Instant::fromEpochMilliseconds),
                        type = CipherHistoryType.OPENED,
                    )
                    changed()
                }
                fillCiphers.forEach { fillCipher ->
                    sqld.cipherUsageHistoryQueries.insert(
                        cipherId = fillCipher.itemId,
                        credentialId = null,
                        createdAt = fillCipher.timestamp
                            .let(Instant::fromEpochMilliseconds),
                        type = CipherHistoryType.USED_AUTOFILL,
                    )
                    changed()
                }
            }
        }

        val dbFile = dbFileIo.bind()
        dbFile.delete()
    }

    private class MigrationLogger(
        private val tag: String,
    ) : MigrationLoggerScope {
        companion object {
            suspend fun log(
                tag: String,
                block: suspend MigrationLoggerScope.() -> Unit,
            ) {
                val logger = MigrationLogger(tag)
                block(logger)
                logger.flush()
            }
        }

        private val now = Clock.System.now()

        var changed = 0
        var ignored = 0

        override fun changed() {
            changed += 1
        }

        override fun ignored() {
            ignored += 1
        }

        fun flush() {
            val dt = Clock.System.now() - now
            Log.i("MigrationLogger", "$tag: Changed $changed, ignored $ignored! It took $dt.")
        }
    }

    interface MigrationLoggerScope {
        fun changed()

        fun ignored()
    }
}
