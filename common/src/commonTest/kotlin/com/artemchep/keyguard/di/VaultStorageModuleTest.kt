package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.data.Database
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.module

class VaultStorageModuleTest {
    @Test
    fun `vault settings and backups resolve the qualified dispatcher without opening the database`() {
        val koin = Koin().apply {
            loadModules(listOf(
                VaultOperationsModule().module,
                VaultTransfersModule().module,
                module {
                    single<CoroutineDispatcher>(named<DatabaseDispatcher>()) { Dispatchers.Default }
                    single<Json> { Json }
                    scope<VaultSessionScope> {
                        scoped<VaultDatabaseManager> { UnopenedDatabase }
                    }
                },
            ), allowOverride = false)
        }
        try {
            val factory = KoinVaultSessionFactory(koin)
            val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
            val first = factory.create(masterKey)
            val second = factory.create(masterKey)
            val firstStore = assertNotNull(first.resolve { get<VaultSettingsKeyValueStore>() })
            val secondStore = assertNotNull(second.resolve { get<VaultSettingsKeyValueStore>() })

            assertSame(firstStore, first.resolve { get<VaultSettingsKeyValueStore>() })
            assertNotSame(firstStore, secondStore)
            assertNotNull(first.resolve { get<BackupConfigRepository>() })
            assertNotNull(second.resolve { get<BackupConfigRepository>() })
        } finally {
            koin.close()
        }
    }

    private object UnopenedDatabase : VaultDatabaseManager {
        override fun get(): IO<Database> = { error("Resolving storage must not open the database") }
        override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> =
            { error("Resolving storage must not mutate the database") }

        override fun changePassword(newMasterKey: MasterKey): IO<Unit> =
            { error("Resolving storage must not rotate the database key") }
    }
}
