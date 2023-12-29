package com.artemchep.keyguard.room

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.artemchep.keyguard.room.dao.AccountDao
import com.artemchep.keyguard.room.dao.CipherDao
import com.artemchep.keyguard.room.dao.CollectionDao
import com.artemchep.keyguard.room.dao.FillCipherHistoryDao
import com.artemchep.keyguard.room.dao.FolderDao
import com.artemchep.keyguard.room.dao.MetaDao
import com.artemchep.keyguard.room.dao.OpenCipherHistoryDao
import com.artemchep.keyguard.room.dao.OrganizationDao
import com.artemchep.keyguard.room.dao.ProfileDao

@Database(
    entities = [
        RoomBitwardenCipher::class,
        RoomBitwardenCollection::class,
        RoomBitwardenOrganization::class,
        RoomBitwardenFolder::class,
        RoomBitwardenProfile::class,
        RoomBitwardenMeta::class,
        RoomBitwardenToken::class,
        // history
        RoomOpenCipherHistory::class,
        RoomFillCipherHistory::class,
    ],
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(RoomSecretConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cipherDao(): CipherDao
    abstract fun collectionDao(): CollectionDao
    abstract fun organizationDao(): OrganizationDao
    abstract fun metaDao(): MetaDao
    abstract fun folderDao(): FolderDao
    abstract fun profileDao(): ProfileDao
    abstract fun accountDao(): AccountDao
    abstract fun fillCipherHistoryDao(): FillCipherHistoryDao
    abstract fun openCipherHistoryDao(): OpenCipherHistoryDao
}
